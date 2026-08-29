package com.openforge.doc.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.doc.client.NumberClient;
import com.openforge.doc.dto.PageResponse;
import com.openforge.doc.entity.DocFile;
import com.openforge.doc.entity.DocInfo;
import com.openforge.doc.mapper.DocFileMapper;
import com.openforge.doc.mapper.DocInfoMapper;
import com.openforge.doc.storage.StorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 文档管理（开发文档 3.2）：主数据、检入检出、文件上传。 */
@Service
@RequiredArgsConstructor
public class DocService {

    static final String NUMBER_RULE_KEY = "doc";

    private final DocInfoMapper docInfoMapper;
    private final DocFileMapper docFileMapper;
    private final NumberClient numberClient;
    private final StorageClient storageClient;
    private final com.openforge.common.event.EventPublisher eventPublisher;

    public DocInfo create(String title, String docType, Long operatorId) {
        DocInfo doc = new DocInfo();
        doc.setDocNumber(numberClient.next(NUMBER_RULE_KEY));
        doc.setTitle(title);
        doc.setDocType(docType == null ? "GENERAL" : docType);
        doc.setVersionMajor("A");
        doc.setVersionMinor(0);
        doc.setLifecycleState("DRAFT");
        doc.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        doc.setCreatedBy(operatorId);
        doc.setDeleted(0);
        docInfoMapper.insert(doc);
        return doc;
    }

    public DocInfo detail(Long id) {
        DocInfo doc = docInfoMapper.selectById(id);
        if (doc == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在");
        }
        return doc;
    }

    public PageResponse<DocInfo> page(long page, long pageSize, String title, String docType) {
        LambdaQueryWrapper<DocInfo> wrapper = new LambdaQueryWrapper<DocInfo>().orderByDesc(DocInfo::getId);
        if (title != null && !title.isBlank()) {
            wrapper.like(DocInfo::getTitle, title.trim());
        }
        if (docType != null && !docType.isBlank()) {
            wrapper.eq(DocInfo::getDocType, docType);
        }
        Page<DocInfo> result = docInfoMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    // ===== 检入检出（开发文档 3.2：检出独占编辑锁） =====

    /** 检出：未检出状态才可检出，锁定为操作人独占。 */
    @Transactional
    public DocInfo checkOut(Long id, Long operatorId) {
        DocInfo doc = detail(id);
        if (doc.getCheckedOutBy() != null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "文档已被检出（用户 " + doc.getCheckedOutBy() + "），禁止重复检出");
        }
        doc.setCheckedOutBy(operatorId);
        doc.setCheckedOutAt(java.time.LocalDateTime.now());
        docInfoMapper.updateById(doc);
        return doc;
    }

    /** 检入：仅检出人可检入，小版本 +1 并解锁。 */
    @Transactional
    public DocInfo checkIn(Long id, Long operatorId) {
        DocInfo doc = detail(id);
        if (doc.getCheckedOutBy() == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "文档未检出，无需检入");
        }
        if (!doc.getCheckedOutBy().equals(operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅检出人可检入");
        }
        doc.setVersionMinor(doc.getVersionMinor() + 1);
        doc.setCheckedOutBy(null);
        doc.setCheckedOutAt(null);
        docInfoMapper.updateById(doc);
        // doc.released（B2 设计 6.2 事件清单：knowledge/search 预留消费）
        eventPublisher.publish("openforge-doc", "doc.released", java.util.Map.of(
                "docId", doc.getId(), "title", doc.getTitle() == null ? "" : doc.getTitle(),
                "versionMinor", doc.getVersionMinor()));
        return doc;
    }

    // ===== 文件 =====

    /** 上传文件到文档（追加新文件记录，存储层落盘，SHA256 校验值入库）。 */
    public DocFile uploadFile(Long docId, String fileName, InputStream content) {
        detail(docId);
        try {
            byte[] bytes = content.readAllBytes();
            String sha256 = sha256(bytes);
            String storageKey = storageClient.save(fileName, new java.io.ByteArrayInputStream(bytes));
            DocFile file = new DocFile();
            file.setDocInfoId(docId);
            file.setFileName(fileName);
            file.setStorageKey(storageKey);
            file.setFileSize((long) bytes.length);
            file.setSha256(sha256);
            docFileMapper.insert(file);
            return file;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件保存失败: " + e.getMessage());
        }
    }

    public List<DocFile> files(Long docId) {
        detail(docId);
        return docFileMapper.selectList(new LambdaQueryWrapper<DocFile>()
                .eq(DocFile::getDocInfoId, docId).orderByDesc(DocFile::getId));
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

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
    private final com.openforge.doc.client.DocNotifyClient notifyClient;
    private final com.openforge.doc.mapper.DocRecycleMapper recycleMapper;

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
        // doc.released（B2 设计 6.2 事件清单：knowledge/search 预留消费）；总线关闭回退站内信订阅通知（v1.24）
        java.util.Map<String, Object> payload = java.util.Map.of(
                "docId", doc.getId(), "title", doc.getTitle() == null ? "" : doc.getTitle(),
                "versionMinor", doc.getVersionMinor());
        if (!eventPublisher.publish("openforge-doc", "doc.released", payload)) {
            notifyClient.deliver("doc.released", payload);
        }
        return doc;
    }

    // ===== 删除/回收站/彻底删除（v1.24）=====

    /** 逻辑删除（仅 DRAFT 且未检出——已发布文档不可删除，与物料/图纸同规）。 */
    @Transactional
    public void delete(Long id) {
        DocInfo doc = docInfoMapper.selectById(id);
        if (doc == null || doc.getDeleted() == 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文档不存在");
        }
        if (!"DRAFT".equals(doc.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅草稿文档可删除，已发布文档不可删除");
        }
        if (doc.getCheckedOutBy() != null) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "检出中的文档不可删除，请先检入");
        }
        docInfoMapper.deleteById(id);
    }

    /** 软删文档列表（回收站）。 */
    public List<DocInfo> trashed() {
        return recycleMapper.trashedDocs();
    }

    /** 恢复软删文档：编号被在册记录占用时明确报错。 */
    @Transactional
    public DocInfo restore(Long id) {
        DocInfo doc = recycleMapper.trashedDoc(id);
        if (doc == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该文档");
        }
        if (recycleMapper.liveCountByNumber(doc.getDocNumber()) > 0) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "恢复失败：编号 " + doc.getDocNumber() + " 已被其他在册文档占用");
        }
        if (recycleMapper.restoreDoc(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该文档");
        }
        return doc;
    }

    /**
     * 彻底删除（不可逆）：文件字节经 StorageClient.delete 逐个 GC（best-effort，失败仅告警不阻断
     * 行删除——MinIO 接线后同接口承接，孤儿文件由存储侧生命周期策略兜底），行级 doc_file/doc_info GC。
     */
    @Transactional
    public DocInfo purge(Long id) {
        DocInfo doc = recycleMapper.trashedDoc(id);
        if (doc == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该文档");
        }
        for (DocFile file : recycleMapper.filesOf(id)) {
            try {
                storageClient.delete(file.getStorageKey());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(DocService.class)
                        .warn("文档文件 GC 失败（继续行删除）: key={} — {}", file.getStorageKey(), e.getMessage());
            }
        }
        recycleMapper.deleteFilesByDoc(id);
        if (recycleMapper.purgeDoc(id) != 1) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "回收站中不存在该文档");
        }
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

    /** 下载/预览源：校验归属后流式读出（doc_file 全局表，租户边界由文档可见性 + 归属校验保证）。 */
    public DownloadPayload download(Long docId, Long fileId) {
        detail(docId);
        DocFile file = docFileMapper.selectById(fileId);
        if (file == null || !file.getDocInfoId().equals(docId)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或不属于该文档");
        }
        try {
            return new DownloadPayload(file, storageClient.load(file.getStorageKey()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
    }

    public record DownloadPayload(DocFile file, InputStream stream) {
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

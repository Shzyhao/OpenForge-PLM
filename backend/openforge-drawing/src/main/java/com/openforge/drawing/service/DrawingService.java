package com.openforge.drawing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.event.EventPublisher;
import com.openforge.common.tenant.TenantContext;
import com.openforge.drawing.client.NumberClient;
import com.openforge.drawing.dto.PageResponse;
import com.openforge.drawing.entity.DrawingFile;
import com.openforge.drawing.entity.DrawingInfo;
import com.openforge.drawing.entity.DrawingPart;
import com.openforge.drawing.entity.DrawingVersion;
import com.openforge.drawing.mapper.DrawingFileMapper;
import com.openforge.drawing.mapper.DrawingInfoMapper;
import com.openforge.drawing.mapper.DrawingPartMapper;
import com.openforge.drawing.mapper.DrawingVersionMapper;
import com.openforge.drawing.storage.StorageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图纸管理（v1.20.0）：档案、文件（主文件/预览副本/附件）、检入检出、生命周期状态机、
 * 发布快照、物料关联。状态机：DRAFT →(submit)→ REVIEWING →(approve)→ RELEASED →(obsolete)→
 * OBSOLETE，REVIEWING 可驳回回 DRAFT；升版 revise 仅 RELEASED 发起（major+1 深拷贝回 DRAFT，
 * 对齐 BOM revise 先例）。发布/作废 afterCommit 发 drawing.released / drawing.obsolete
 * （topic openforge-drawing）——失败由 EventPublisher 落 outbox/熔断，不阻断业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawingService {

    static final String NUMBER_RULE_KEY = "drawing";
    static final String TOPIC = "openforge-drawing";
    private static final Set<String> FILE_KINDS = Set.of("MAIN", "PREVIEW", "ATTACHMENT");
    private static final Set<String> LINK_ROLES = Set.of("PART_DRAWING", "ASSEMBLY", "REFERENCE");

    private final DrawingInfoMapper drawingMapper;
    private final DrawingFileMapper fileMapper;
    private final DrawingVersionMapper versionMapper;
    private final DrawingPartMapper partMapper;
    private final NumberClient numberClient;
    private final StorageClient storageClient;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final com.openforge.drawing.client.AuthAuditClient auditClient;

    // ===== 档案 =====

    @Transactional
    public DrawingInfo create(String title, Long operatorId) {
        DrawingInfo drawing = new DrawingInfo();
        drawing.setDrawingNumber(numberClient.next(NUMBER_RULE_KEY));
        drawing.setTitle(title);
        drawing.setVersionMajor("A");
        drawing.setVersionMinor(0);
        drawing.setLifecycleState("DRAFT");
        drawing.setTenantId(TenantContext.getTenantId());
        drawing.setCreatedBy(operatorId);
        drawing.setDeleted(0);
        drawingMapper.insert(drawing);
        auditClient.record(operatorId, "DRW_CREATE", "DRAWING", drawing.getDrawingNumber(), drawing.getTitle());
        return drawing;
    }

    public DrawingInfo detail(Long id) {
        return requireDrawing(id);
    }

    public PageResponse<DrawingInfo> page(long page, long pageSize, String title, String state, Long partId) {
        List<Long> drawingIds = null;
        if (partId != null) {
            drawingIds = partMapper.selectList(new LambdaQueryWrapper<DrawingPart>()
                            .eq(DrawingPart::getPartId, partId))
                    .stream().map(DrawingPart::getDrawingId).toList();
            if (drawingIds.isEmpty()) {
                return new PageResponse<>(List.of(), 0, page, pageSize);
            }
        }
        LambdaQueryWrapper<DrawingInfo> wrapper = new LambdaQueryWrapper<DrawingInfo>().orderByDesc(DrawingInfo::getId);
        if (title != null && !title.isBlank()) {
            wrapper.like(DrawingInfo::getTitle, title.trim());
        }
        if (state != null && !state.isBlank()) {
            wrapper.eq(DrawingInfo::getLifecycleState, state);
        }
        if (drawingIds != null) {
            wrapper.in(DrawingInfo::getId, drawingIds);
        }
        Page<DrawingInfo> result = drawingMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 逻辑删除（仅 DRAFT——进入评审/发布后走 obsolete 终态追溯）。 */
    @Transactional
    public void delete(Long id) {
        DrawingInfo drawing = requireDrawing(id);
        if (!"DRAFT".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "仅草稿可删除，已进入流程的图纸请走作废");
        }
        drawingMapper.deleteById(id);
        auditClient.record(null, "DRW_DELETE", "DRAWING", drawing.getDrawingNumber(), drawing.getTitle());
    }

    // ===== 文件 =====

    /** 上传（仅 DRAFT；同 kind 的 PREVIEW 可重复上传替换语义由前端管理——文件仅追加）。 */
    @Transactional
    public DrawingFile uploadFile(Long id, String fileName, InputStream content, String kind) throws Exception {
        DrawingInfo drawing = requireDrawing(id);
        requireDraft(drawing, "上传文件");
        String normalizedKind = kind == null || kind.isBlank() ? "ATTACHMENT" : kind.trim();
        if (!FILE_KINDS.contains(normalizedKind)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "文件类型须为 MAIN/PREVIEW/ATTACHMENT");
        }
        byte[] bytes = content.readAllBytes();
        DrawingFile file = new DrawingFile();
        file.setDrawingId(id);
        file.setFileName(fileName == null ? "unnamed" : fileName);
        file.setStorageKey(storageClient.save(fileName, new java.io.ByteArrayInputStream(bytes)));
        file.setFileSize((long) bytes.length);
        file.setSha256(sha256(bytes));
        file.setKind(normalizedKind);
        file.setTenantId(TenantContext.getTenantId());
        fileMapper.insert(file);
        return file;
    }

    public List<DrawingFile> files(Long id) {
        requireDrawing(id);
        return fileMapper.selectList(new LambdaQueryWrapper<DrawingFile>()
                .eq(DrawingFile::getDrawingId, id).orderByAsc(DrawingFile::getId));
    }

    /** 下载/预览源：校验归属后流式读出（租户隔离由拦截器保证 file 行可见性）。 */
    public DownloadPayload download(Long id, Long fileId) {
        requireDrawing(id);
        DrawingFile file = fileMapper.selectById(fileId);
        if (file == null || !file.getDrawingId().equals(id)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "文件不存在或不属于该图纸");
        }
        try {
            return new DownloadPayload(file, storageClient.load(file.getStorageKey()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("图纸文件读取失败 fileId={}", fileId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文件读取失败");
        }
    }

    public record DownloadPayload(DrawingFile file, InputStream stream) {
    }

    // ===== 检入检出与生命周期 =====

    @Transactional
    public DrawingInfo checkOut(Long id, Long operatorId) {
        DrawingInfo drawing = requireDrawing(id);
        requireDraft(drawing, "检出");
        if (drawing.getCheckedOutBy() != null) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "图纸已被检出");
        }
        drawing.setCheckedOutBy(operatorId);
        drawing.setCheckedOutAt(java.time.LocalDateTime.now());
        drawingMapper.updateById(drawing);
        return drawing;
    }

    /** 检入：小版本 +1，解锁。 */
    @Transactional
    public DrawingInfo checkIn(Long id, Long operatorId) {
        DrawingInfo drawing = requireDrawing(id);
        requireDraft(drawing, "检入");
        if (!operatorId.equals(drawing.getCheckedOutBy())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅检出人可检入");
        }
        drawing.setVersionMinor(drawing.getVersionMinor() + 1);
        drawing.setCheckedOutBy(null);
        drawing.setCheckedOutAt(null);
        drawingMapper.updateById(drawing);
        return drawing;
    }

    @Transactional
    public DrawingInfo submit(Long id) {
        DrawingInfo drawing = requireDrawing(id);
        requireDraft(drawing, "提交评审");
        if (drawing.getCheckedOutBy() != null) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "检出中的图纸不能提交，请先检入");
        }
        boolean hasMain = !fileMapper.selectList(new LambdaQueryWrapper<DrawingFile>()
                .eq(DrawingFile::getDrawingId, id).eq(DrawingFile::getKind, "MAIN")).isEmpty();
        if (!hasMain) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "提交评审前须上传主文件（MAIN）");
        }
        drawing.setLifecycleState("REVIEWING");
        drawingMapper.updateById(drawing);
        return drawing;
    }

    @Transactional
    public DrawingInfo approve(Long id, Long operatorId) {
        DrawingInfo drawing = requireDrawing(id);
        if (!"REVIEWING".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅评审中的图纸可发布");
        }
        drawing.setLifecycleState("RELEASED");
        drawingMapper.updateById(drawing);
        saveSnapshot(drawing, operatorId);
        auditClient.record(operatorId, "DRW_PUBLISH", "DRAWING", drawing.getDrawingNumber(),
                "发布版本 " + drawing.version());
        publishAfterCommit("drawing.released", Map.of(
                "drawingId", drawing.getId(),
                "drawingNumber", drawing.getDrawingNumber(),
                "title", drawing.getTitle(),
                "version", drawing.version()));
        return drawing;
    }

    @Transactional
    public DrawingInfo reject(Long id) {
        DrawingInfo drawing = requireDrawing(id);
        if (!"REVIEWING".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅评审中的图纸可驳回");
        }
        drawing.setLifecycleState("DRAFT");
        drawingMapper.updateById(drawing);
        return drawing;
    }

    @Transactional
    public DrawingInfo obsolete(Long id) {
        DrawingInfo drawing = requireDrawing(id);
        if (!"RELEASED".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅已发布图纸可作废");
        }
        drawing.setLifecycleState("OBSOLETE");
        drawingMapper.updateById(drawing);
        auditClient.record(null, "DRW_OBSOLETE", "DRAWING", drawing.getDrawingNumber(), "作废");
        publishAfterCommit("drawing.obsolete", Map.of(
                "drawingId", drawing.getId(),
                "drawingNumber", drawing.getDrawingNumber(),
                "version", drawing.version()));
        return drawing;
    }

    /** 升版（仅 RELEASED 发起）：大版本 +1、小版本归零、回 DRAFT；文件随档案延续。 */
    @Transactional
    public DrawingInfo revise(Long id) {
        DrawingInfo drawing = requireDrawing(id);
        if (!"RELEASED".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "仅已发布图纸可升版");
        }
        char major = drawing.getVersionMajor().charAt(0);
        if (major >= 'Z') {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "大版本已至上限 Z，请归档重建");
        }
        drawing.setVersionMajor(String.valueOf((char) (major + 1)));
        drawing.setVersionMinor(0);
        drawing.setLifecycleState("DRAFT");
        drawingMapper.updateById(drawing);
        auditClient.record(null, "DRW_REVISE", "DRAWING", drawing.getDrawingNumber(),
                "升版 " + drawing.version());
        return drawing;
    }

    // ===== 物料关联 =====

    /** partNumber 由前端自物料选择器带入（服务端不做跨域存在性校验——编码为冗余快照，物料删档不影响追溯）。 */
    @Transactional
    public DrawingPart linkPart(Long id, Long partId, String partNumber, String role) {
        DrawingInfo drawing = requireDrawing(id);
        String normalizedRole = role == null || role.isBlank() ? "PART_DRAWING" : role.trim();
        if (!LINK_ROLES.contains(normalizedRole)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "关联角色须为 PART_DRAWING/ASSEMBLY/REFERENCE");
        }
        Long existed = partMapper.selectCount(new LambdaQueryWrapper<DrawingPart>()
                .eq(DrawingPart::getDrawingId, id).eq(DrawingPart::getPartId, partId));
        if (existed > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "该物料已关联此图纸");
        }
        DrawingPart link = new DrawingPart();
        link.setDrawingId(id);
        link.setPartId(partId);
        link.setPartNumber(partNumber);
        link.setRole(normalizedRole);
        link.setTenantId(TenantContext.getTenantId());
        partMapper.insert(link);
        log.debug("图纸关联物料 drawing={} part={} role={}", drawing.getDrawingNumber(), partNumber, normalizedRole);
        return link;
    }

    @Transactional
    public void unlinkPart(Long id, Long partId) {
        requireDrawing(id);
        partMapper.delete(new LambdaQueryWrapper<DrawingPart>()
                .eq(DrawingPart::getDrawingId, id).eq(DrawingPart::getPartId, partId));
    }

    public List<DrawingPart> parts(Long id) {
        requireDrawing(id);
        return partMapper.selectList(new LambdaQueryWrapper<DrawingPart>()
                .eq(DrawingPart::getDrawingId, id).orderByAsc(DrawingPart::getId));
    }

    public List<DrawingVersion> versions(Long id) {
        requireDrawing(id);
        return versionMapper.selectList(new LambdaQueryWrapper<DrawingVersion>()
                .eq(DrawingVersion::getDrawingId, id).orderByDesc(DrawingVersion::getId));
    }

    /** 按物料反查图纸（含图纸主档摘要，供物料详情侧跳转）。 */
    public List<Map<String, Object>> byPart(Long partId) {
        List<DrawingPart> links = partMapper.selectList(new LambdaQueryWrapper<DrawingPart>()
                .eq(DrawingPart::getPartId, partId).orderByAsc(DrawingPart::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (DrawingPart link : links) {
            DrawingInfo drawing = drawingMapper.selectById(link.getDrawingId());
            if (drawing == null) {
                continue; // 图纸已删（关联残留防御）
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("drawingId", drawing.getId());
            row.put("drawingNumber", drawing.getDrawingNumber());
            row.put("title", drawing.getTitle());
            row.put("version", drawing.version());
            row.put("lifecycleState", drawing.getLifecycleState());
            row.put("role", link.getRole());
            out.add(row);
        }
        return out;
    }

    // ===== 内部 =====

    private DrawingInfo requireDrawing(Long id) {
        DrawingInfo drawing = drawingMapper.selectById(id);
        if (drawing == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "图纸不存在");
        }
        return drawing;
    }

    private void requireDraft(DrawingInfo drawing, String action) {
        if (!"DRAFT".equals(drawing.getLifecycleState())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "仅草稿状态可" + action + "（当前 " + drawing.getLifecycleState() + "）");
        }
    }

    private void saveSnapshot(DrawingInfo drawing, Long operatorId) {
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("drawingNumber", drawing.getDrawingNumber());
            snapshot.put("title", drawing.getTitle());
            snapshot.put("versionMajor", drawing.getVersionMajor());
            snapshot.put("versionMinor", drawing.getVersionMinor());
            snapshot.put("files", files(drawing.getId()).stream().map(f -> Map.of(
                    "fileName", f.getFileName(), "kind", f.getKind(),
                    "fileSize", f.getFileSize(), "sha256", f.getSha256())).toList());
            DrawingVersion version = new DrawingVersion();
            version.setDrawingId(drawing.getId());
            version.setVersion(drawing.version());
            version.setSnapshot(objectMapper.writeValueAsString(snapshot));
            version.setState("RELEASED");
            version.setReleasedBy(operatorId);
            version.setTenantId(TenantContext.getTenantId());
            versionMapper.insert(version);
        } catch (Exception e) {
            log.error("图纸版本快照失败 drawingId={}", drawing.getId(), e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "版本快照失败");
        }
    }

    private void publishAfterCommit(String event, Map<String, Object> payload) {
        Runnable emit = () -> eventPublisher.publish(TOPIC, event, payload);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emit.run();
                        }
                    });
        } else {
            emit.run();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "摘要计算失败");
        }
    }
}

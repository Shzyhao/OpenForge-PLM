package com.openforge.metadata.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.metadata.dto.CreateMetaObjectRequest;
import com.openforge.metadata.dto.DdlPreviewResponse;
import com.openforge.metadata.dto.MetaObjectDetailResponse;
import com.openforge.metadata.dto.MetaObjectSummaryResponse;
import com.openforge.metadata.dto.PageResponse;
import com.openforge.metadata.dto.UpdateMetaObjectRequest;
import com.openforge.metadata.service.MetaObjectService;
import com.openforge.metadata.service.MetaPublishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 元对象建模 API（F2 设计 3）。发布（publish）随 F2-2/F2-3 发布流水线提供。
 */
@RestController
@RequestMapping("/api/v1/meta/objects")
@RequiredArgsConstructor
public class MetaObjectController {

    private final MetaObjectService metaObjectService;
    private final MetaPublishService metaPublishService;

    @PostMapping
    @RequirePermission("meta:manage")
    public ApiResponse<MetaObjectDetailResponse> create(
            @Valid @RequestBody CreateMetaObjectRequest request, HttpServletRequest http) {
        return ApiResponse.ok(metaObjectService.create(request, currentUserId(http)));
    }

    @GetMapping
    public ApiResponse<PageResponse<MetaObjectSummaryResponse>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(metaObjectService.page(page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<MetaObjectDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(metaObjectService.detail(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("meta:manage")
    public ApiResponse<MetaObjectDetailResponse> update(
            @PathVariable Long id, @Valid @RequestBody UpdateMetaObjectRequest request) {
        return ApiResponse.ok(metaObjectService.update(id, request));
    }

    /** DDL 预览：与发布执行语句同源（F2 设计 5），供发布前确认。 */
    @GetMapping("/{id}/ddl")
    @RequirePermission("meta:manage")
    public ApiResponse<DdlPreviewResponse> previewDdl(@PathVariable Long id) {
        return ApiResponse.ok(metaObjectService.previewDdl(id));
    }

    /**
     * 发布（F2 设计 5）：校验引用闭合 → 生成并执行 DDL（安全门）→ 写版本快照 → PUBLISHED。
     * 权限点创建与 Schema 知识同步随 F2-3 接入。
     */
    @PostMapping("/{id}/publish")
    @RequirePermission("meta:manage")
    public ApiResponse<java.util.Map<String, Object>> publish(
            @PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(metaPublishService.publish(id, currentUserId(http)));
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

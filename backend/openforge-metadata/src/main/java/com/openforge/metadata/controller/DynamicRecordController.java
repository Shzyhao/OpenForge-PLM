package com.openforge.metadata.controller;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.metadata.dto.PageResponse;
import com.openforge.metadata.service.DynamicRecordService;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 动态对象记录 API（F2 设计 3）：发布后即刻可用。
 * 权限为编程式校验（非注解）——权限码 `{objectKey}:view/create/update/delete` 运行时取自元数据，
 * 发布时由流水线自动创建权限点（F2-3）。身份来自网关信任头 X-User-Id，SUPER 免检。
 */
@RestController
@RequestMapping("/api/v1/objects/{objectKey}/records")
@RequiredArgsConstructor
public class DynamicRecordController {

    private final DynamicRecordService dynamicRecordService;
    private final PermissionQueryClient permissionQueryClient;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @PathVariable String objectKey, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        requirePermission(objectKey + ":create", request);
        return ApiResponse.ok(dynamicRecordService.create(objectKey, body, currentUserId(request)));
    }

    @GetMapping
    public ApiResponse<PageResponse<Map<String, Object>>> page(
            @PathVariable String objectKey,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        requirePermission(objectKey + ":view", request);
        // getParameterValues 直取原始值：Spring 的 List 绑定会把逗号拆成多值，破坏 in 过滤语义
        String[] filters = request.getParameterValues("filter");
        var result = dynamicRecordService.page(objectKey, page, pageSize,
                filters == null ? List.of() : List.of(filters), sort);
        return ApiResponse.ok(new PageResponse<>(result.total(), result.page(), result.pageSize(), result.items()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String objectKey, @PathVariable Long id, HttpServletRequest request) {
        requirePermission(objectKey + ":view", request);
        return ApiResponse.ok(dynamicRecordService.detail(objectKey, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String objectKey, @PathVariable Long id,
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        requirePermission(objectKey + ":update", request);
        return ApiResponse.ok(dynamicRecordService.update(objectKey, id, body, currentUserId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @PathVariable String objectKey, @PathVariable Long id, HttpServletRequest request) {
        requirePermission(objectKey + ":delete", request);
        dynamicRecordService.delete(objectKey, id, currentUserId(request));
        return ApiResponse.ok();
    }

    /** 编程式权限校验（F2 设计 4：权限码含运行时元数据，注解无法表达）。 */
    private void requirePermission(String code, HttpServletRequest request) {
        Long userId = currentUserId(request);
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少网关信任头，请经由网关访问");
        }
        PermissionView view = permissionQueryClient.fetch(userId);
        if ("SUPER".equals(view.userType())) {
            return;
        }
        if (!view.permissions().contains(code)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无操作权限: " + code);
        }
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

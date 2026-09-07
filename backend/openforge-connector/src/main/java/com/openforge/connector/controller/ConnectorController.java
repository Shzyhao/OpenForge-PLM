package com.openforge.connector.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.connector.dto.ConnDetailResponse;
import com.openforge.connector.dto.ConnSummaryResponse;
import com.openforge.connector.dto.InvokeRequest;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.SaveConnRequest;
import com.openforge.connector.service.ConnectorDefinitionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 连接器定义 API（集成编排器 MVP 设计 §6）。运行时 invoke 端点随刀2 交付。
 */
@RestController
@RequestMapping("/api/v1/connectors")
@RequiredArgsConstructor
public class ConnectorController {

    private final ConnectorDefinitionService definitionService;

    @PostMapping
    @RequirePermission("conn:manage")
    public ApiResponse<ConnDetailResponse> create(
            @Valid @RequestBody SaveConnRequest request, HttpServletRequest http) {
        return ApiResponse.ok(definitionService.create(request, currentUserId(http)));
    }

    @GetMapping
    public ApiResponse<PageResponse<ConnSummaryResponse>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(definitionService.page(page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConnDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(definitionService.detail(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("conn:manage")
    public ApiResponse<ConnDetailResponse> update(
            @PathVariable Long id, @Valid @RequestBody SaveConnRequest request, HttpServletRequest http) {
        return ApiResponse.ok(definitionService.update(id, request, currentUserId(http)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("conn:manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        definitionService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/publish")
    @RequirePermission("conn:manage")
    public ApiResponse<Map<String, Object>> publish(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(definitionService.publish(id, currentUserId(http)));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("conn:manage")
    public ApiResponse<Map<String, Object>> disable(@PathVariable Long id, HttpServletRequest http) {
        return ApiResponse.ok(definitionService.disable(id, currentUserId(http)));
    }

    /** 设计时试运行（trigger=MANUAL，落执行日志）。 */
    @PostMapping("/{id}/test")
    @RequirePermission("conn:manage")
    public ApiResponse<InvokeResponse> test(
            @PathVariable Long id, @RequestBody(required = false) InvokeRequest request) {
        return ApiResponse.ok(definitionService.test(id, request == null ? new InvokeRequest() : request));
    }

    /** 执行日志（已脱敏，分页）。 */
    @GetMapping("/{id}/exec-logs")
    @RequirePermission("conn:manage")
    public ApiResponse<PageResponse<com.openforge.connector.dto.ExecLogResponse>> execLogs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(definitionService.execLogs(id, page, pageSize));
    }

    /**
     * 运行时调用（仅已发布连接器；trigger=API）：前端/脚本经网关调用。
     * 未发布 6004 / 停用 6005 / 不存在 6002。
     */
    @PostMapping("/invoke/{connCode}")
    @RequirePermission("conn:invoke")
    public ApiResponse<InvokeResponse> invoke(
            @PathVariable String connCode, @RequestBody(required = false) InvokeRequest request) {
        return ApiResponse.ok(definitionService.invoke(connCode,
                request == null || request.getParams() == null ? java.util.Map.of() : request.getParams()));
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

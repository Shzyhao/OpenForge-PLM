package com.openforge.connector.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.connector.dto.AiProviderResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.ProviderTestResponse;
import com.openforge.connector.dto.SaveAiProviderRequest;
import com.openforge.connector.service.AiProviderService;
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

/**
 * AI 供应商管理 API（集成编排器 MVP 设计 §12.1）：列表按降级链优先级排序；响应永不回显 apiKey。
 */
@RestController
@RequestMapping("/api/v1/ai-providers")
@RequiredArgsConstructor
public class AiProviderController {

    private final AiProviderService providerService;

    @PostMapping
    @RequirePermission("ai:manage")
    public ApiResponse<AiProviderResponse> create(
            @Valid @RequestBody SaveAiProviderRequest request, HttpServletRequest http) {
        return ApiResponse.ok(providerService.create(request, currentUserId(http)));
    }

    @GetMapping
    public ApiResponse<PageResponse<AiProviderResponse>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(providerService.page(page, pageSize));
    }

    @PutMapping("/{id}")
    @RequirePermission("ai:manage")
    public ApiResponse<AiProviderResponse> update(
            @PathVariable Long id, @RequestBody SaveAiProviderRequest request, HttpServletRequest http) {
        return ApiResponse.ok(providerService.update(id, request, currentUserId(http)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("ai:manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return ApiResponse.ok(null);
    }

    /** 连通性测试（GET {baseUrl}/models；key 不出服务）。 */
    @PostMapping("/{id}/test")
    @RequirePermission("ai:manage")
    public ApiResponse<ProviderTestResponse> test(@PathVariable Long id) {
        return ApiResponse.ok(providerService.test(id));
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

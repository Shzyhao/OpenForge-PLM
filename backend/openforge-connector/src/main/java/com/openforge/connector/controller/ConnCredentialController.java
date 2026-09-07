package com.openforge.connector.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.connector.dto.CredentialResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.SaveCredentialRequest;
import com.openforge.connector.service.CredentialService;
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
 * 凭据管理 API（集成编排器 MVP 设计 §6/§7）：响应永不回显凭据明文/密文。
 */
@RestController
@RequestMapping("/api/v1/connector-credentials")
@RequiredArgsConstructor
public class ConnCredentialController {

    private final CredentialService credentialService;

    @PostMapping
    @RequirePermission("conn:manage")
    public ApiResponse<CredentialResponse> create(
            @Valid @RequestBody SaveCredentialRequest request, HttpServletRequest http) {
        return ApiResponse.ok(credentialService.create(request, currentUserId(http)));
    }

    @GetMapping
    public ApiResponse<PageResponse<CredentialResponse>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(credentialService.page(page, pageSize));
    }

    @PutMapping("/{id}")
    @RequirePermission("conn:manage")
    public ApiResponse<CredentialResponse> update(
            @PathVariable Long id, @RequestBody SaveCredentialRequest request, HttpServletRequest http) {
        return ApiResponse.ok(credentialService.update(id, request, currentUserId(http)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("conn:manage")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest http) {
        credentialService.delete(id, currentUserId(http));
        return ApiResponse.ok(null);
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

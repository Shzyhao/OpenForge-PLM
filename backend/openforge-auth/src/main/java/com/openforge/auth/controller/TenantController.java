package com.openforge.auth.controller;

import com.openforge.auth.entity.SysTenant;
import com.openforge.auth.service.TenantService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 租户管理 API（F3-1，tenant:manage）。 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    @RequirePermission("tenant:manage")
    public ApiResponse<List<SysTenant>> list() {
        return ApiResponse.ok(tenantService.list());
    }

    @PostMapping
    @RequirePermission("tenant:manage")
    public ApiResponse<SysTenant> create(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponse.ok(tenantService.create(request.getTenantCode(), request.getTenantName(), request.getRemark()));
    }

    @PostMapping("/{id}/disable")
    @RequirePermission("tenant:manage")
    public ApiResponse<Void> disable(@PathVariable Long id) {
        tenantService.toggle(id, false);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/enable")
    @RequirePermission("tenant:manage")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        tenantService.toggle(id, true);
        return ApiResponse.ok();
    }

    /** 用户归属调整（重新登录后生效）。 */
    @PostMapping("/{id}/users/{userId}")
    @RequirePermission("tenant:manage")
    public ApiResponse<Void> assignUser(@PathVariable Long id, @PathVariable Long userId) {
        tenantService.assignUser(id, userId);
        return ApiResponse.ok();
    }

    @Data
    public static class CreateTenantRequest {
        @NotBlank
        private String tenantCode;
        @NotBlank
        private String tenantName;
        private String remark;
    }
}

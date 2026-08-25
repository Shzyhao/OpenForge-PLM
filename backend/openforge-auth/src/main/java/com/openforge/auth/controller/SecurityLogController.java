package com.openforge.auth.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.auth.entity.SysAuditLog;
import com.openforge.auth.entity.SysLoginLog;
import com.openforge.auth.service.SecurityLogService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 安全日志查询（方案 F6/F8；user:manage 保护）。 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityLogController {

    private final SecurityLogService securityLogService;

    @GetMapping("/login-logs")
    @RequirePermission("user:manage")
    public ApiResponse<Page<SysLoginLog>> loginLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String username) {
        return ApiResponse.ok(securityLogService.loginLogs(page, pageSize, username));
    }

    @GetMapping("/audit-logs")
    @RequirePermission("user:manage")
    public ApiResponse<Page<SysAuditLog>> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String action) {
        return ApiResponse.ok(securityLogService.auditLogs(page, pageSize, action));
    }
}

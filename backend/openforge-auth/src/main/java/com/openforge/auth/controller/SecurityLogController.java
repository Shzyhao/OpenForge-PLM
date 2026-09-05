package com.openforge.auth.controller;

import com.openforge.auth.dto.PageResponse;
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

/**
 * 安全日志查询（方案 F6/F8；user:manage 保护）。
 * 分页统一 PageResponse{list,total,page,pageSize}——此前直返 MyBatis-Plus Page
 * （records/size 字段名），前端读 list 得 undefined：表格"暂无数据"而总数正常，
 * 全页面浏览器级巡检实锤（约定 #9）。
 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class SecurityLogController {

    private final SecurityLogService securityLogService;

    @GetMapping("/login-logs")
    @RequirePermission("user:manage")
    public ApiResponse<PageResponse<SysLoginLog>> loginLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String username) {
        var p = securityLogService.loginLogs(page, pageSize, username);
        return ApiResponse.ok(new PageResponse<>(
                p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }

    @GetMapping("/audit-logs")
    @RequirePermission("user:manage")
    public ApiResponse<PageResponse<SysAuditLog>> auditLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String action) {
        var p = securityLogService.auditLogs(page, pageSize, action);
        return ApiResponse.ok(new PageResponse<>(
                p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
}

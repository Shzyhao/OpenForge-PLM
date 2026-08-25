package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.auth.entity.SysAuditLog;
import com.openforge.auth.entity.SysLoginLog;
import com.openforge.auth.mapper.AuditLogMapper;
import com.openforge.auth.mapper.LoginLogMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 安全日志（方案 F6 登录日志 / F7 权限变更审计）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityLogService {

    private final LoginLogMapper loginLogMapper;
    private final AuditLogMapper auditLogMapper;

    public void recordLogin(String username, boolean success, String reason, String ip, String userAgent) {
        try {
            SysLoginLog entry = new SysLoginLog();
            entry.setUsername(username);
            entry.setSuccess(success ? 1 : 0);
            entry.setReason(reason);
            entry.setIp(ip);
            entry.setUserAgent(userAgent == null ? null
                    : userAgent.substring(0, Math.min(userAgent.length(), 255)));
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("login log failed: {}", e.getMessage()); // 日志失败不阻断登录
        }
    }

    public void audit(Long operatorId, String action, String targetType, String targetId, String detail) {
        try {
            SysAuditLog entry = new SysAuditLog();
            entry.setOperatorId(operatorId);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detail);
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("audit log failed: {}", e.getMessage());
        }
    }

    public Page<SysLoginLog> loginLogs(long page, long pageSize, String username) {
        LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<SysLoginLog>()
                .orderByDesc(SysLoginLog::getId);
        if (username != null && !username.isBlank()) {
            wrapper.like(SysLoginLog::getUsername, username.trim());
        }
        return loginLogMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
    }

    public Page<SysAuditLog> auditLogs(long page, long pageSize, String action) {
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<SysAuditLog>()
                .orderByDesc(SysAuditLog::getId);
        if (action != null && !action.isBlank()) {
            wrapper.like(SysAuditLog::getAction, action.trim());
        }
        return auditLogMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
    }
}

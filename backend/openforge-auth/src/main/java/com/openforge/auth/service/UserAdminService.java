package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.auth.dto.CreateUserRequest;
import com.openforge.auth.dto.PageResponse;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户管理（方案 D 组 + A3 admin 保护矩阵）。
 * 保护规则：目标为 SUPER（固定 admin）时——除 admin 本人外的任何操作一律拒绝；
 * admin 永不可停用/删除；任何用户不可删除自己。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RbacService rbacService;
    private final PasswordEncoder passwordEncoder;
    private final com.openforge.auth.mapper.PasswordHistoryMapper passwordHistoryMapper;
    private final SecurityLogService securityLogService;

    @Value("${openforge.security.password-expiry-days:180}")
    private int passwordExpiryDays;

    // ===== 保护矩阵（方案 3.3） =====

    private void assertCanOperate(Long operatorId, SysUser target, boolean selfAllowed) {
        if ("SUPER".equals(target.getUserType())) {
            if (!selfAllowed) {
                throw new BizException(ErrorCode.FORBIDDEN, "固定管理员账号不支持该操作");
            }
            if (!target.getId().equals(operatorId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "只有 admin 本人可以修改 admin 账号");
            }
        }
    }

    // ===== D2 创建 =====

    @Transactional
    public SysUser create(CreateUserRequest request, Long operatorId) {
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (existing != null && existing > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        validatePasswordStrength(request.getPassword());

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName() == null ? request.getUsername() : request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setOrgId(request.getOrgId());
        user.setStatus("ACTIVE");
        user.setUserType("NORMAL");
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setFirstLoginChange(1); // 创建的账号首登强制改密（方案 D2/E6）
        user.setFailedLoginCount(0);
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);

        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            rbacService.assignRoles(user.getId(), request.getRoleIds());
        }
        securityLogService.audit(operatorId, "USER_CREATE", "USER",
                String.valueOf(user.getId()), "创建用户 " + user.getUsername());
        log.info("user created: {} by operator {}", user.getUsername(), operatorId);
        return user;
    }

    // ===== D1 列表 / D10 详情 =====

    public PageResponse<SysUser> page(long page, long pageSize, String username, Long roleId, String status) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getId);
        if (username != null && !username.isBlank()) {
            wrapper.like(SysUser::getUsername, username.trim());
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(SysUser::getStatus, status);
        }
        if (roleId != null) {
            List<Long> userIds = userRoleMapper.selectList(
                            new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                    .stream().map(SysUserRole::getUserId).toList();
            if (userIds.isEmpty()) {
                return new PageResponse<>(List.of(), 0, page, pageSize);
            }
            wrapper.in(SysUser::getId, userIds);
        }
        Page<SysUser> result = userMapper.selectPage(Page.of(page, Math.min(pageSize, 200)), wrapper);
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public SysUser require(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    // ===== D3 编辑 =====

    @Transactional
    public SysUser update(Long id, Long operatorId, String displayName, String email, Long orgId) {
        SysUser target = require(id);
        assertCanOperate(operatorId, target, true);
        if (displayName != null && !displayName.isBlank()) {
            target.setDisplayName(displayName);
        }
        if (email != null) {
            target.setEmail(email);
        }
        if (orgId != null) {
            target.setOrgId(orgId);
        }
        userMapper.updateById(target);
        return target;
    }

    // ===== D4 启停用 =====

    @Transactional
    public SysUser changeStatus(Long id, Long operatorId, boolean enable) {
        SysUser target = require(id);
        if ("SUPER".equals(target.getUserType())) {
            throw new BizException(ErrorCode.FORBIDDEN, "固定管理员账号不可停用");
        }
        if (id.equals(operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能停用自己的账号");
        }
        target.setStatus(enable ? "ACTIVE" : "DISABLED");
        userMapper.updateById(target);
        securityLogService.audit(operatorId, enable ? "USER_ENABLE" : "USER_DISABLE", "USER",
                String.valueOf(id), (enable ? "启用" : "停用") + "用户 " + target.getUsername());
        return target;
    }

    // ===== D5 重置密码 =====

    @Transactional
    public String resetPassword(Long id, Long operatorId, String newPassword) {
        SysUser target = require(id);
        assertCanOperate(operatorId, target, true);
        validatePasswordStrength(newPassword);
        assertNotRecentPassword(id, newPassword, target.getPasswordHash());
        recordPasswordHistory(id, newPassword);
        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setPasswordUpdatedAt(LocalDateTime.now());
        target.setFirstLoginChange(1); // 重置后下次登录强制改密（方案 E6）
        target.setFailedLoginCount(0);
        target.setLockedUntil(null);
        userMapper.updateById(target);
        securityLogService.audit(operatorId, "USER_RESET_PASSWORD", "USER",
                String.valueOf(id), "重置用户 " + target.getUsername() + " 的密码");
        log.info("password reset by operator {} for user {}", operatorId, target.getUsername());
        return newPassword;
    }

    // ===== D6 删除 =====

    @Transactional
    public void delete(Long id, Long operatorId) {
        SysUser target = require(id);
        if ("SUPER".equals(target.getUserType())) {
            throw new BizException(ErrorCode.FORBIDDEN, "固定管理员账号不可删除");
        }
        if (id.equals(operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "不能删除自己的账号");
        }
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        userMapper.deleteById(id);
        securityLogService.audit(operatorId, "USER_DELETE", "USER",
                String.valueOf(id), "删除用户 " + target.getUsername());
        log.info("user deleted: {} by operator {}", target.getUsername(), operatorId);
    }

    // ===== E5 修改自己的密码 =====

    @Transactional
    public void changeMyPassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = require(userId);
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "原密码不正确");
        }
        validatePasswordStrength(newPassword);
        assertNotRecentPassword(userId, newPassword, user.getPasswordHash());
        recordPasswordHistory(userId, newPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setFirstLoginChange(0); // 完成改密，解除强制状态
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        userMapper.updateById(user);
        securityLogService.audit(userId, "PASSWORD_CHANGE", "USER",
                String.valueOf(userId), "用户 " + user.getUsername() + " 修改了自己的密码");
        log.info("password changed by user {}", user.getUsername());
    }

    /** 批量启停（方案 D9）：逐个走保护矩阵，任一被拒整体回滚（事务内）。 */
    @Transactional
    public java.util.List<String> changeStatusBatch(java.util.List<Long> ids, Long operatorId, boolean enable) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (Long id : ids) {
            SysUser target = require(id);
            if ("SUPER".equals(target.getUserType())) {
                throw new BizException(ErrorCode.FORBIDDEN, "固定管理员账号不可停用: " + target.getUsername());
            }
            if (id.equals(operatorId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "不能停用自己的账号: " + target.getUsername());
            }
            target.setStatus(enable ? "ACTIVE" : "DISABLED");
            userMapper.updateById(target);
            changed.add(target.getUsername());
            securityLogService.audit(operatorId, enable ? "USER_ENABLE" : "USER_DISABLE", "USER",
                    String.valueOf(id), "批量" + (enable ? "启用" : "停用") + "用户 " + target.getUsername());
        }
        return changed;
    }

    /** 密码历史（方案 E8）：新密码不得与当前密码及最近 3 次历史重复。 */
    private void assertNotRecentPassword(Long userId, String newPassword, String currentHash) {
        if (currentHash != null && passwordEncoder.matches(newPassword, currentHash)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "新密码与当前密码相同，请更换");
        }
        var recent = passwordHistoryMapper.selectList(
                new LambdaQueryWrapper<com.openforge.auth.entity.SysPasswordHistory>()
                        .eq(com.openforge.auth.entity.SysPasswordHistory::getUserId, userId)
                        .orderByDesc(com.openforge.auth.entity.SysPasswordHistory::getId)
                        .last("LIMIT 3"));
        boolean reused = recent.stream().anyMatch(
                h -> passwordEncoder.matches(newPassword, h.getPasswordHash()));
        if (reused) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "新密码与最近使用的密码重复，请更换");
        }
    }

    private void recordPasswordHistory(Long userId, String rawPassword) {
        com.openforge.auth.entity.SysPasswordHistory h = new com.openforge.auth.entity.SysPasswordHistory();
        h.setUserId(userId);
        h.setPasswordHash(passwordEncoder.encode(rawPassword));
        passwordHistoryMapper.insert(h);
    }

    /** 密码强度（方案 E7 最小集）：≥8 位且同时包含字母与数字。 */
    static void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BizException(ErrorCode.WEAK_PASSWORD);
        }
    }
}

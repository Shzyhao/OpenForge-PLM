package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysTenant;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.TenantMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 租户管理（F3-1）：主档 CRUD + 用户归属调整 + 开通流水线（十轮改进）。
 * 全部端点仅平台租户(0)操作者可用（十轮 F12：sys_tenant/sys_user 全局表被 tenant:manage
 * 持有者跨租户读写——租户 1 管理员可搬动任意用户入本租户）。
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecurityLogService securityLogService;

    /** 租户管理是平台级操作：非平台租户操作者一律拒绝。 */
    private void assertPlatformOperator() {
        Long tenantId = com.openforge.common.tenant.TenantContext.getTenantId();
        if (tenantId == null || tenantId != 0L) {
            throw new BizException(ErrorCode.FORBIDDEN, "租户管理仅平台管理员可操作");
        }
    }

    public List<SysTenant> list() {
        assertPlatformOperator();
        return tenantMapper.selectList(
                new LambdaQueryWrapper<SysTenant>().orderByAsc(SysTenant::getId));
    }

    public SysTenant create(String tenantCode, String tenantName, String remark) {
        assertPlatformOperator();
        Long existed = tenantMapper.selectCount(
                new LambdaQueryWrapper<SysTenant>().eq(SysTenant::getTenantCode, tenantCode));
        if (existed > 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "租户编码已存在: " + tenantCode);
        }
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(tenantName);
        tenant.setEnabled(1);
        tenant.setRemark(remark);
        tenantMapper.insert(tenant);
        securityLogService.audit(null, "TENANT_CREATE", "TENANT", tenantCode, tenantName);
        return tenant;
    }

    /**
     * 开通流水线（十轮改进）：建租户 + 初始管理员 + 绑定 ADMINS 角色，单事务。
     * 管理员首登强制改密；编号规则/流程定义为平台模板（GLOBAL_TABLES），新租户开箱可用。
     */
    @Transactional
    public Map<String, Object> onboard(String tenantCode, String tenantName, String remark,
                                       String adminUsername, String adminPassword, String adminDisplayName) {
        assertPlatformOperator();
        SysTenant tenant = create(tenantCode, tenantName, remark);
        Long existed = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, adminUsername));
        if (existed != null && existed > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        UserAdminService.validatePasswordStrength(adminPassword);

        SysUser admin = new SysUser();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setDisplayName(adminDisplayName == null || adminDisplayName.isBlank()
                ? adminUsername : adminDisplayName);
        admin.setStatus("ACTIVE");
        admin.setUserType("NORMAL");
        admin.setTenantId(tenant.getId());
        admin.setPasswordUpdatedAt(java.time.LocalDateTime.now());
        admin.setFirstLoginChange(1);
        admin.setFailedLoginCount(0);
        admin.setDeleted(0);
        userMapper.insert(admin);

        com.openforge.auth.entity.SysRole admins = roleMapper.selectOne(
                new LambdaQueryWrapper<com.openforge.auth.entity.SysRole>()
                        .eq(com.openforge.auth.entity.SysRole::getRoleCode, "ADMINS"));
        if (admins == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "ADMINS 角色不存在，无法绑定初始管理员");
        }
        SysUserRole binding = new SysUserRole();
        binding.setUserId(admin.getId());
        binding.setRoleId(admins.getId());
        userRoleMapper.insert(binding);

        securityLogService.audit(null, "TENANT_ONBOARD", "TENANT", tenantCode,
                "开通租户并创建初始管理员 " + adminUsername);
        return Map.of("tenant", tenant, "adminUserId", admin.getId(), "adminUsername", adminUsername);
    }

    /** 停用租户：归属用户随登录校验被拒（tenant.enabled=1 才可登录）。 */
    public void toggle(Long tenantId, boolean enabled) {
        assertPlatformOperator();
        SysTenant tenant = requireTenant(tenantId);
        tenant.setEnabled(enabled ? 1 : 0);
        tenantMapper.updateById(tenant);
        securityLogService.audit(null, enabled ? "TENANT_ENABLE" : "TENANT_DISABLE",
                "TENANT", tenant.getTenantCode(), null);
    }

    /** 用户归属调整：登录后 JWT 携带新租户，行级隔离随之切换。 */
    @Transactional
    public void assignUser(Long tenantId, Long userId) {
        assertPlatformOperator();
        requireTenant(tenantId);
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if ("SUPER".equals(user.getUserType())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "admin 不归属业务租户");
        }
        Long before = user.getTenantId();
        user.setTenantId(tenantId);
        userMapper.updateById(user);
        securityLogService.audit(null, "TENANT_ASSIGN_USER", "USER", String.valueOf(userId),
                "tenant " + before + " -> " + tenantId);
    }

    private SysTenant requireTenant(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        return tenant;
    }
}

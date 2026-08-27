package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysTenant;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.TenantMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 租户管理（F3-1）：主档 CRUD + 用户归属调整。 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final SecurityLogService securityLogService;

    public List<SysTenant> list() {
        return tenantMapper.selectList(
                new LambdaQueryWrapper<SysTenant>().orderByAsc(SysTenant::getId));
    }

    public SysTenant create(String tenantCode, String tenantName, String remark) {
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

    /** 停用租户：归属用户随登录校验被拒（tenant.enabled=1 才可登录）。 */
    public void toggle(Long tenantId, boolean enabled) {
        SysTenant tenant = requireTenant(tenantId);
        tenant.setEnabled(enabled ? 1 : 0);
        tenantMapper.updateById(tenant);
        securityLogService.audit(null, enabled ? "TENANT_ENABLE" : "TENANT_DISABLE",
                "TENANT", tenant.getTenantCode(), null);
    }

    /** 用户归属调整：登录后 JWT 携带新租户，行级隔离随之切换。 */
    @Transactional
    public void assignUser(Long tenantId, Long userId) {
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

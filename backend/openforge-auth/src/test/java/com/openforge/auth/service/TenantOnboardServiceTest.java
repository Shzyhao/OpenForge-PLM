package com.openforge.auth.service;

import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysTenant;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.TenantMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户开通流水线（十轮改进）：建租户+初始管理员+绑定 ADMINS 单事务；
 * 全部租户管理操作仅平台租户(0)可操作（十轮 F12：assignUser 等可被租户管理员跨租户搬人）。
 */
@SpringBootTest
class TenantOnboardServiceTest {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final AtomicLong SEQ = new AtomicLong();

    private Long createdTenantId;
    private Long createdAdminId;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (createdAdminId != null) {
            userRoleMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, createdAdminId));
            userMapper.deleteById(createdAdminId);
        }
        if (createdTenantId != null) {
            tenantMapper.deleteById(createdTenantId);
        }
    }

    @Test
    @DisplayName("开通：租户+管理员落库，管理员归属新租户、绑定 ADMINS、首登强制改密")
    void onboardCreatesTenantAndAdmin() {
        TenantContext.setTenantId(0L);
        long seq = SEQ.incrementAndGet();
        Map<String, Object> result = tenantService.onboard("onb_t" + seq, "开通测试租户", "r11",
                "onb_admin" + seq, "PasswordA1", "开通管理员");
        SysTenant tenant = (SysTenant) result.get("tenant");
        createdTenantId = tenant.getId();
        createdAdminId = ((Number) result.get("adminUserId")).longValue();

        SysUser admin = userMapper.selectById(createdAdminId);
        assertThat(admin).isNotNull();
        assertThat(admin.getTenantId()).isEqualTo(createdTenantId);
        assertThat(admin.getFirstLoginChange()).isEqualTo(1);
        assertThat(passwordEncoder.matches("PasswordA1", admin.getPasswordHash())).isTrue();

        SysRole admins = roleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, "ADMINS"));
        Long bound = userRoleMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, createdAdminId)
                .eq(SysUserRole::getRoleId, admins.getId()));
        assertThat(bound).isEqualTo(1L);
    }

    @Test
    @DisplayName("非平台租户操作者：list/onboard/assignUser 一律拒绝（跨租户管理面收口）")
    void nonPlatformOperatorDenied() {
        TenantContext.setTenantId(1L);
        assertThatThrownBy(() -> tenantService.list())
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tenantService.onboard("onb_x", "x", null, "onb_y", "PasswordA1", null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tenantService.assignUser(1L, 999L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("弱密码/重复用户名拒绝开通")
    void onboardValidation() {
        TenantContext.setTenantId(0L);
        long seq = SEQ.incrementAndGet();
        assertThatThrownBy(() -> tenantService.onboard("onb_w" + seq, "弱密码", null,
                "onb_wa" + seq, "short", null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> tenantService.onboard("onb_d" + seq, "重复名", null,
                "admin", "PasswordA1", null))
                .isInstanceOf(BizException.class);
    }
}

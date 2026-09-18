package com.openforge.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 用户管理租户边界（R10）：sys_user 是全局表（登录需按用户名跨租户寻址），租户拦截器不覆盖，
 * 此前租户 1 管理员可列出/改/删租户 0 用户（实测删除成功，P0）。修复后服务层自守：
 * 平台租户(0)操作者管理全部租户，其余操作者跨租户一律按"用户不存在"拒绝；passwordHash 永不序列化。
 */
@SpringBootTest
class UserTenantBoundaryServiceTest {

    @Autowired
    private UserAdminService userAdminService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;

    private static final AtomicLong SEQ = new AtomicLong();

    private Long t0UserId;
    private Long t1UserId;
    private Long t1OperatorId;

    private Long insertUser(String username, long tenantId, String userType) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("PasswordA1"));
        user.setStatus("ACTIVE");
        user.setUserType(userType);
        user.setTenantId(tenantId);
        user.setDeleted(0);
        userMapper.insert(user);
        return user.getId();
    }

    @BeforeEach
    void setUp() {
        long seq = SEQ.incrementAndGet();
        t0UserId = insertUser("r10t0u" + seq, 0L, "NORMAL");
        t1UserId = insertUser("r10t1u" + seq, 1L, "NORMAL");
        t1OperatorId = insertUser("r10t1op" + seq, 1L, "SUPER");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (t0UserId != null) {
            userMapper.deleteById(t0UserId);
        }
        if (t1UserId != null) {
            userMapper.deleteById(t1UserId);
        }
        if (t1OperatorId != null) {
            userMapper.deleteById(t1OperatorId);
        }
    }

    @Test
    @DisplayName("跨租户删用户被拒且目标无恙（修复前实测删除成功）")
    void crossTenantDeleteDenied() {
        TenantContext.setTenantId(1L);
        assertThatThrownBy(() -> userAdminService.delete(t0UserId, t1OperatorId))
                .isInstanceOf(BizException.class);
        SysUser survivor = userMapper.selectById(t0UserId);
        assertThat(survivor).isNotNull();
        assertThat(survivor.getDeleted()).isZero();
    }

    @Test
    @DisplayName("跨租户编辑被拒；本租户编辑放行")
    void crossTenantUpdateDenied() {
        TenantContext.setTenantId(1L);
        assertThatThrownBy(() -> userAdminService.update(t0UserId, t1OperatorId, "hacked", null, null))
                .isInstanceOf(BizException.class);
        assertThatCode(() -> userAdminService.update(t1UserId, t1OperatorId, "ok", null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("列表租户过滤：租户 1 仅见本租户；平台租户 0 见全部")
    void pageFilteredByTenant() {
        TenantContext.setTenantId(1L);
        var t1Page = userAdminService.page(1, 50, null, null, null);
        assertThat(t1Page.list()).extracting(SysUser::getId)
                .doesNotContain(t0UserId)
                .contains(t1UserId);

        TenantContext.setTenantId(0L);
        var t0Page = userAdminService.page(1, 50, null, null, null);
        assertThat(t0Page.list()).extracting(SysUser::getId)
                .contains(t0UserId, t1UserId);
    }

    @Test
    @DisplayName("passwordHash 永不出现在序列化输出（修复前用户列表响应携带哈希）")
    void passwordHashNeverSerialized() throws Exception {
        TenantContext.setTenantId(0L);
        SysUser user = userMapper.selectById(t0UserId);
        String json = objectMapper.writeValueAsString(user);
        assertThat(json).doesNotContain("passwordHash").doesNotContain("$2");
    }
}

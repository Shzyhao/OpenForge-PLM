package com.openforge.auth.config;

import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理员初始密码引导（A1 改造 2026-11）：可配置来源、默认值兜底、首登强制改密保留。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private UserMapper userMapper;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private SysUser pendingAdmin() {
        SysUser admin = new SysUser();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setPasswordHash(AdminBootstrap.PENDING_HASH);
        return admin;
    }

    private AdminBootstrap bootstrap(String configuredPassword) {
        AdminBootstrap bootstrap = new AdminBootstrap(userMapper, encoder);
        ReflectionTestUtils.setField(bootstrap, "configuredPassword", configuredPassword);
        return bootstrap;
    }

    @Test
    @DisplayName("显式配置的初始密码（含首尾空白）写入哈希并保留首登强制改密")
    void explicitPasswordIsHashedWithFirstLoginChange() {
        SysUser admin = pendingAdmin();
        when(userMapper.selectOne(any())).thenReturn(admin);

        bootstrap("  BootTest2026x  ").run(null);

        verify(userMapper).updateById(admin);
        assertThat(encoder.matches("BootTest2026x", admin.getPasswordHash())).isTrue();
        assertThat(admin.getFirstLoginChange()).isEqualTo(1);
    }

    @Test
    @DisplayName("未配置时回落公开默认密码（与冒烟脚本一致）")
    void missingConfigFallsBackToPublicDefault() {
        SysUser admin = pendingAdmin();
        when(userMapper.selectOne(any())).thenReturn(admin);

        bootstrap("").run(null);

        verify(userMapper).updateById(admin);
        assertThat(encoder.matches(AdminBootstrap.DEFAULT_INITIAL_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(admin.getFirstLoginChange()).isEqualTo(1);
    }

    @Test
    @DisplayName("已有密码的 admin 不被打扰")
    void existingPasswordIsLeftAlone() {
        SysUser admin = pendingAdmin();
        admin.setPasswordHash("$2a$10$already-set");
        when(userMapper.selectOne(any())).thenReturn(admin);

        bootstrap("BootTest2026x").run(null);

        verify(userMapper, never()).updateById(any(SysUser.class));
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$already-set");
    }
}

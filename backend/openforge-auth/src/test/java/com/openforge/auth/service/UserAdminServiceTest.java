package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.dto.CreateUserRequest;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** admin 保护矩阵（方案 3.3）与用户管理核心规则。 */
@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RbacService rbacService;
    @Mock
    private com.openforge.auth.mapper.PasswordHistoryMapper passwordHistoryMapper;
    @Mock
    private SecurityLogService securityLogService;

    private UserAdminService service;

    private static final Long ADMIN_ID = 1L;    // 固定 admin（SUPER）
    private static final Long ADMINS_USER = 2L; // ADMINS 角色用户
    private static final Long NORMAL_USER = 3L;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userMapper, userRoleMapper, roleMapper, rbacService,
                new BCryptPasswordEncoder(), passwordHistoryMapper, securityLogService);
        ReflectionTestUtils.setField(service, "passwordExpiryDays", 180);
    }

    private SysUser user(Long id, String userType) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("user" + id);
        u.setUserType(userType);
        u.setStatus("ACTIVE");
        u.setFailedLoginCount(0);
        u.setFirstLoginChange(0);
        u.setPasswordHash(new BCryptPasswordEncoder().encode("oldpass123"));
        u.setPasswordUpdatedAt(LocalDateTime.now());
        return u;
    }

    @Test
    @DisplayName("保护矩阵：ADMINS 用户不可修改 admin（R3 核心）")
    void adminsCannotModifyAdmin() {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, "SUPER"));
        assertThatThrownBy(() -> service.update(ADMIN_ID, ADMINS_USER, "hack", null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 admin 本人");
        assertThatThrownBy(() -> service.resetPassword(ADMIN_ID, ADMINS_USER, "NewPass1234"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只有 admin 本人");
    }

    @Test
    @DisplayName("保护矩阵：admin 可修改自己；admin 不可被停用/删除（任何人）")
    void adminSelfModifyAndImmutable() {
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, "SUPER"));
        when(passwordHistoryMapper.selectList(any())).thenReturn(java.util.List.of());
        when(passwordHistoryMapper.insert(any(com.openforge.auth.entity.SysPasswordHistory.class))).thenReturn(1);
        assertThatCode(() -> service.update(ADMIN_ID, ADMIN_ID, "Admin", null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.resetPassword(ADMIN_ID, ADMIN_ID, "NewPass1234"))
                .doesNotThrowAnyException();
        // 不可停用/删除——即使是 admin 自己
        assertThatThrownBy(() -> service.changeStatus(ADMIN_ID, ADMIN_ID, false))
                .isInstanceOf(BizException.class).hasMessageContaining("不可停用");
        assertThatThrownBy(() -> service.delete(ADMIN_ID, ADMIN_ID))
                .isInstanceOf(BizException.class).hasMessageContaining("不可删除");
    }

    @Test
    @DisplayName("保护矩阵：不能停用/删除自己（普通用户）")
    void cannotDisableOrDeleteSelf() {
        when(userMapper.selectById(NORMAL_USER)).thenReturn(user(NORMAL_USER, "NORMAL"));
        assertThatThrownBy(() -> service.changeStatus(NORMAL_USER, NORMAL_USER, false))
                .isInstanceOf(BizException.class).hasMessageContaining("自己");
        assertThatThrownBy(() -> service.delete(NORMAL_USER, NORMAL_USER))
                .isInstanceOf(BizException.class).hasMessageContaining("自己");
    }

    @Test
    @DisplayName("批量启停：含 admin 目标整体拒绝（事务语义）")
    void batchStatusProtectsAdmin() {
        when(userMapper.selectById(NORMAL_USER)).thenReturn(user(NORMAL_USER, "NORMAL"));
        when(userMapper.selectById(ADMIN_ID)).thenReturn(user(ADMIN_ID, "SUPER"));
        assertThatThrownBy(() -> service.changeStatusBatch(
                java.util.List.of(NORMAL_USER, ADMIN_ID), ADMINS_USER, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("固定管理员");
    }

    @Test
    @DisplayName("创建用户：首登强制改密 + 密码强度校验")
    void createUserValidates() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            inv.getArgument(0, SysUser.class).setId(9L);
            return 1;
        });
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("newuser");
        req.setPassword("Str0ngPass");
        SysUser created = service.create(req, ADMIN_ID);
        assertThat(created.getFirstLoginChange()).isEqualTo(1);
        assertThat(created.getUserType()).isEqualTo("NORMAL");

        req.setPassword("weak"); // 强度不足
        assertThatThrownBy(() -> service.create(req, ADMIN_ID))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.WEAK_PASSWORD));
    }

    @Test
    @DisplayName("修改自己的密码：旧密错误拒绝；成功后解除强制改密状态")
    void changeMyPasswordFlow() {
        SysUser u = user(NORMAL_USER, "NORMAL");
        u.setFirstLoginChange(1);
        when(userMapper.selectById(NORMAL_USER)).thenReturn(u);
        when(passwordHistoryMapper.selectList(any())).thenReturn(java.util.List.of());
        when(passwordHistoryMapper.insert(any(com.openforge.auth.entity.SysPasswordHistory.class))).thenReturn(1);

        assertThatThrownBy(() -> service.changeMyPassword(NORMAL_USER, "wrongpass1", "NewPass1234"))
                .isInstanceOf(BizException.class);

        service.changeMyPassword(NORMAL_USER, "oldpass123", "NewPass1234");
        assertThat(u.getFirstLoginChange()).isEqualTo(0);
        assertThat(u.getPasswordUpdatedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
    }
}

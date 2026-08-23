package com.openforge.auth.service;

import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RbacServiceTest {

    @Mock
    private RoleMapper roleMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private UserMapper userMapper;

    private RbacService rbacService;

    @BeforeEach
    void setUp() {
        rbacService = new RbacService(roleMapper, userRoleMapper, userMapper);
    }

    @Test
    @DisplayName("创建角色：编码重复应抛 ROLE_CODE_ALREADY_EXISTS")
    void createRoleDuplicateShouldFail() {
        when(roleMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> rbacService.createRole("QA", "质量"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ROLE_CODE_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("分配角色：包含不存在的 roleId 应抛 ROLE_NOT_FOUND 且不落库")
    void assignRolesWithUnknownRoleShouldFail() {
        when(roleMapper.selectCount(any())).thenReturn(1L); // 传了 2 个角色但只有 1 个有效

        assertThatThrownBy(() -> rbacService.assignRoles(1L, List.of(10L, 999L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ROLE_NOT_FOUND));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    @DisplayName("分配角色：先清空旧绑定再全量插入（覆盖语义）")
    void assignRolesShouldReplaceAll() {
        when(roleMapper.selectCount(any())).thenReturn(2L);

        rbacService.assignRoles(1L, List.of(10L, 20L, 20L)); // 含重复，应去重后插入 2 条

        verify(userRoleMapper, times(1)).delete(any());
        ArgumentCaptor<SysUserRole> captor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SysUserRole::getRoleId)
                .containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("分配空角色列表：仅清空绑定")
    void assignEmptyRolesShouldOnlyDelete() {
        rbacService.assignRoles(1L, List.of());

        verify(userRoleMapper, times(1)).delete(any());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }
}

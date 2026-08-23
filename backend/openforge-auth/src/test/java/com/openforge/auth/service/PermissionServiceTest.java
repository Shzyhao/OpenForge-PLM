package com.openforge.auth.service;

import com.openforge.auth.entity.SysRole;
import com.openforge.auth.mapper.PermissionMapper;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.RolePermissionMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private PermissionMapper permissionMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RolePermissionMapper rolePermissionMapper;
    @Mock
    private UserRoleMapper userRoleMapper;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(permissionMapper, roleMapper, rolePermissionMapper, userRoleMapper);
    }

    @Test
    @DisplayName("创建权限点：编码重复应抛 PERMISSION_CODE_ALREADY_EXISTS")
    void createPermissionDuplicateShouldFail() {
        when(permissionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> permissionService.createPermission("part:create", "创建物料"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PERMISSION_CODE_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("绑定角色权限：角色不存在应抛 ROLE_NOT_FOUND")
    void bindToUnknownRoleShouldFail() {
        when(roleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> permissionService.bindRolePermissions(999L, List.of(1L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    @DisplayName("绑定角色权限：包含未知权限点应抛 PERMISSION_NOT_FOUND")
    void bindWithUnknownPermissionShouldFail() {
        SysRole role = new SysRole();
        role.setId(1L);
        when(roleMapper.selectById(1L)).thenReturn(role);
        when(permissionMapper.selectCount(any())).thenReturn(1L); // 传 2 个只有 1 个有效

        assertThatThrownBy(() -> permissionService.bindRolePermissions(1L, List.of(10L, 999L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PERMISSION_NOT_FOUND));
    }

    @Test
    @DisplayName("用户无任何角色时权限联查返回空列表")
    void userWithoutRolesHasNoPermissions() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(permissionService.getPermissionCodesOfUser(1L)).isEmpty();
    }
}

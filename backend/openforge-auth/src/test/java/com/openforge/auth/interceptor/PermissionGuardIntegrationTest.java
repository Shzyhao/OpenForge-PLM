package com.openforge.auth.interceptor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限拦截端到端验证（MockMvc 模拟网关信任头）：
 * 无头 401 / 无角色 403 / ADMIN 免检 / 持权非 ADMIN 通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PermissionGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private com.openforge.auth.service.PermissionService permissionService;

    private Long createdUserId;

    @AfterEach
    void cleanup() {
        if (createdUserId != null) {
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, createdUserId));
            userMapper.deleteById(createdUserId);
            createdUserId = null;
        }
    }

    private Long createUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash("$2a$10$placeholderhashplaceholderhashplaceholderha");
        user.setStatus("ACTIVE");
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);
        createdUserId = user.getId();
        return user.getId();
    }

    private void bindRole(Long userId, String roleCode) {
        SysRole role = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, roleCode));
        SysUserRole ur = new SysUserRole();
        ur.setUserId(userId);
        ur.setRoleId(role.getId());
        userRoleMapper.insert(ur);
    }

    @Test
    @DisplayName("缺少网关信任头：401 code=2001")
    void missingHeaderShouldReturn401() throws Exception {
        mockMvc.perform(put("/api/v1/roles/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("无角色用户访问受保护接口：403 code=2004")
    void userWithoutRoleShouldReturn403() throws Exception {
        Long uid = createUser("guard_norole");

        mockMvc.perform(put("/api/v1/roles/users/{id}", uid)
                        .header("X-User-Id", String.valueOf(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2004));
    }

    @Test
    @DisplayName("ADMINS 角色用户经由全权限点绑定通过校验（V15 后 ADMINS 持全部权限）")
    void adminsShouldPassGuard() throws Exception {
        Long uid = createUser("guard_admins");
        bindRole(uid, "ADMINS");

        mockMvc.perform(put("/api/v1/roles/users/{id}", uid)
                        .header("X-User-Id", String.valueOf(uid))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("V14 后固定 admin 账号为 SUPER 免检；V15 后 ADMINS 持有全部权限点")
    void superAdminAndAdminsPermissions() {
        Long adminsUid = createUser("guard_admins_perms");
        bindRole(adminsUid, "ADMINS");

        com.openforge.auth.service.PermissionService ps = permissionService;
        assertThat(ps.getPermissionCodesOfUser(adminsUid))
                .contains("role:create", "role:assign", "perm:manage", "user:manage",
                        "menu:material", "material:view"); // 操作点 + 菜单 + view 全量绑定
    }
}

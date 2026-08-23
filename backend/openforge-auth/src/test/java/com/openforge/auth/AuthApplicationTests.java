package com.openforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.entity.SysUserRole;
import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.mapper.UserRoleMapper;
import com.openforge.auth.service.RbacService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成冒烟：H2(PostgreSQL 模式) 上验证 Flyway 迁移可执行、MyBatis-Plus 映射正确。
 * 对应 Loop Engineering 的 V3 级验证（依赖环境的确定性验证）。
 */
@SpringBootTest
class AuthApplicationTests {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private RbacService rbacService;

    @Test
    @DisplayName("Flyway 迁移 + Mapper 读写往返")
    void mapperRoundTripOnMigratedSchema() {
        SysUser user = new SysUser();
        user.setUsername("smoke_user");
        user.setPasswordHash("$2a$10$placeholderhashplaceholderhashplaceholderha");
        user.setDisplayName("冒烟用户");
        user.setStatus("ACTIVE");
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);
        assertThat(user.getId()).isNotNull();

        SysUser loaded = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "smoke_user"));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getDisplayName()).isEqualTo("冒烟用户");
        assertThat(loaded.getCreatedAt()).isNotNull();

        userMapper.deleteById(loaded.getId());
        assertThat(userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "smoke_user"))).isZero();
    }

    @Test
    @DisplayName("V2 迁移：内置角色就位，分配与联查往返正确")
    void rbacMigrationAndAssignmentRoundTrip() {
        // 内置角色由 V2 迁移插入
        Long builtinCount = roleMapper.selectCount(null);
        assertThat(builtinCount).isGreaterThanOrEqualTo(3);

        // 建用户 → 分配 ENGINEER(通过编码查 id) → 联查角色编码
        SysUser user = new SysUser();
        user.setUsername("rbac_smoke_user");
        user.setPasswordHash("$2a$10$placeholderhashplaceholderhashplaceholderha");
        user.setStatus("ACTIVE");
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);

        SysRole engineer = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "ENGINEER"));
        rbacService.assignRoles(user.getId(), List.of(engineer.getId()));

        assertThat(rbacService.getRoleCodesOfUser(user.getId()))
                .containsExactly("ENGINEER");

        // 覆盖式再分配 → 只剩 VIEWER
        SysRole viewer = roleMapper.selectOne(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, "VIEWER"));
        rbacService.assignRoles(user.getId(), List.of(viewer.getId()));
        assertThat(rbacService.getRoleCodesOfUser(user.getId()))
                .containsExactly("VIEWER");

        // 清理
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId()));
        userMapper.deleteById(user.getId());
    }
}

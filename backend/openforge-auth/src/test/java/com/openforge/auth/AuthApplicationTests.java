package com.openforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成冒烟：H2(PostgreSQL 模式) 上验证 Flyway 迁移可执行、MyBatis-Plus 映射正确。
 * 对应 Loop Engineering 的 V3 级验证（依赖环境的确定性验证）。
 */
@SpringBootTest
class AuthApplicationTests {

    @Autowired
    private UserMapper userMapper;

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
}

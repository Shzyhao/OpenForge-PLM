package com.openforge.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 PostgreSQL 集成测试矩阵（框架化路线 F1）：
 * 覆盖 H2 单库测试盲区——Flyway V1~V16 全量迁移、admin 引导、注册管控、登录失败语义在真实方言下的行为。
 * Docker 不可用（含 Windows desktop-linux context 场景）自动跳过；手动启停容器避免 @Container 先于 assume 执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthPostgresContainerTest {

    private static PostgreSQLContainer<?> pg;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @BeforeAll
    static void startContainerIfDockerAvailable() {
        boolean available;
        try {
            available = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available,
                "Docker 不可用（或 desktop-linux context 未暴露默认管道），跳过真实 PG 集成测试");
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
    }

    @AfterAll
    static void stopContainer() {
        if (pg != null && pg.isRunning()) {
            pg.stop();
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        if (pg != null && pg.isRunning()) {
            registry.add("spring.datasource.url", pg::getJdbcUrl);
            registry.add("spring.datasource.username", pg::getUsername);
            registry.add("spring.datasource.password", pg::getPassword);
        }
    }

    @Test
    @DisplayName("真实 PG：Flyway V1~V16 全量迁移 + admin(SUPER) 引导 + 注册管控 + 登录失败")
    void fullMigrationAndAdminFlowOnRealPostgres() throws Exception {
        SysUser admin = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        assertThat(admin).isNotNull();
        assertThat(admin.getUserType()).isEqualTo("SUPER");
        assertThat(admin.getPasswordHash()).isNotEqualTo("PENDING_BOOTSTRAP");

        // 注册默认关闭（D8）
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"random\",\"password\":\"Passw0rd1\"}"))
                .andExpect(jsonPath("$.code").value(2004));

        // 登录失败语义（真实 PG 下失败计数落库）
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"WrongPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002));
    }
}

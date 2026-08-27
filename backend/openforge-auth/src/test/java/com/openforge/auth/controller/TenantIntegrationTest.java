package com.openforge.auth.controller;

import com.openforge.auth.entity.SysTenant;
import com.openforge.auth.mapper.TenantMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F3-1 多租户（H2）：租户主档 CRUD、用户归属调整、JWT 携带租户声明。
 * 行级隔离的 SQL 自动过滤由 metadata 动态表用例与各服务既有套件（租户 0 语义不变）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private com.openforge.auth.service.TenantService tenantService;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("租户管理：令牌门禁 + 创建/查重/启停")
    void tenantCrud() throws Exception {
        mockMvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isUnauthorized());   // 无信任头（auth 库直查拦截器）

        mockMvc.perform(post("/api/v1/tenants")
                        .contentType("application/json")
                        .content("{\"tenantCode\":\"acme\",\"tenantName\":\"Acme 制造\"}"))
                .andExpect(status().isUnauthorized());

        SysTenant tenant = tenantService.create("acme", "Acme 制造", "F3 用例");
        assertThat(tenant.getEnabled()).isEqualTo(1);

        // 编码查重
        try {
            tenantService.create("acme", "重复", null);
            throw new AssertionError("重复编码应被拒绝");
        } catch (com.openforge.common.api.BizException e) {
            assertThat(e.getMessage()).contains("已存在");
        }

        tenantService.toggle(tenant.getId(), false);
        assertThat(tenantMapper.selectById(tenant.getId()).getEnabled()).isZero();
    }

    @Test
    @DisplayName("用户归属 + JWT 租户声明：换租户后令牌携带新租户")
    void userAssignmentAndJwtClaim() {
        SysTenant tenant = tenantService.create("branch_" + System.nanoTime(), "分部", null);

        // 真实落一个 NORMAL 用户后调整归属（SUPER 拒绝调整）
        com.openforge.auth.entity.SysUser user = new com.openforge.auth.entity.SysUser();
        user.setUsername("tenant_user_" + System.nanoTime());
        user.setPasswordHash("x");
        user.setDisplayName("租户用户");
        user.setStatus("ACTIVE");
        user.setUserType("NORMAL");
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);

        tenantService.assignUser(tenant.getId(), user.getId());
        assertThat(userMapper.selectById(user.getId()).getTenantId()).isEqualTo(tenant.getId());

        com.openforge.auth.entity.SysUser admin = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.openforge.auth.entity.SysUser>()
                        .eq(com.openforge.auth.entity.SysUser::getUsername, "admin"));
        try {
            tenantService.assignUser(tenant.getId(), admin.getId());
            throw new AssertionError("SUPER 归属调整应被拒绝");
        } catch (com.openforge.common.api.BizException e) {
            assertThat(e.getMessage()).contains("admin");
        }

        // JWT 携带租户声明（网关转发 X-User-Tenant 的数据源）
        String token = jwtService.generate(user.getId(), user.getUsername(), "租户用户", tenant.getId());
        assertThat(jwtService.parse(token).get("tenant", Long.class)).isEqualTo(tenant.getId());
        assertThat(jwtService.parse(jwtService.generate(user.getId(), user.getUsername(), "租户用户", null))
                .get("tenant", Long.class)).isEqualTo(0L);
    }
}

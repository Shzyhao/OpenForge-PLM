package com.openforge.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A4-1 模块注册中心集成验证：自注册幂等 upsert、路由安全红线
 * （白名单/内核前缀防劫持/前缀占用冲突）、模块列表 API。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModuleRegistryIntegrationTest {

    private static final String TOKEN = "openforge-internal-dev-token";

    @Autowired
    private MockMvc mockMvc;

    private void register(String body) throws Exception {
        mockMvc.perform(post("/api/v1/internal/modules")
                        .header("X-Internal-Token", TOKEN)
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
    }

    @Test
    @DisplayName("注册中心：令牌防护 + 幂等 upsert + 前端/管理列表")
    void registerAndList() throws Exception {
        // 无令牌拒绝
        mockMvc.perform(post("/api/v1/internal/modules")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 首次注册（BUSINESS + 菜单贡献）
        register("""
                {"moduleKey":"doc","moduleType":"BUSINESS","displayName":"文档","version":"0.1.0",
                 "routes":["/api/v1/docs"],"menu":[{"path":"/doc","title":"文档"}],"dependencies":[]}
                """);
        // 幂等 upsert：版本刷新不报冲突
        register("""
                {"moduleKey":"doc","moduleType":"BUSINESS","displayName":"文档","version":"0.2.0",
                 "routes":["/api/v1/docs"],"menu":[{"path":"/doc","title":"文档"}],"dependencies":[]}
                """);

        // 前端列表：启用模块 + 菜单
        mockMvc.perform(get("/api/v1/modules"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='doc')].version").value(org.hamcrest.Matchers.hasItem("0.2.0")))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='doc')].menu").isNotEmpty());

        // 管理列表需要 module:manage（无信任头 → 401）
        mockMvc.perform(get("/api/v1/modules/admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("安全红线：内核前缀劫持/非法路由/前缀占用 拒绝")
    void registrationRedLines() throws Exception {
        // 先注册占位模块（避免与另一用例的执行顺序耦合）
        register("""
                {"moduleKey":"conflict_probe","moduleType":"BUSINESS","displayName":"探针","version":"1",
                 "routes":["/api/v1/probe-a"],"dependencies":[]}
                """);

        // 劫持内核前缀
        mockMvc.perform(post("/api/v1/internal/modules").header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"moduleKey\":\"evil\",\"moduleType\":\"BUSINESS\",\"version\":\"1\",\n"
                                + "   \"routes\":[\"/api/v1/auth\"]}"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("内核")));
        // 注入形态路由
        mockMvc.perform(post("/api/v1/internal/modules").header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"moduleKey\":\"evil2\",\"moduleType\":\"BUSINESS\",\"version\":\"1\",\n"
                                + "   \"routes\":[\"/api/v1/x; DROP TABLE sys_user\"]}"))
                .andExpect(jsonPath("$.code").value(1000));
        // 与占位模块路由前缀冲突
        mockMvc.perform(post("/api/v1/internal/modules").header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"moduleKey\":\"conflict_probe2\",\"moduleType\":\"BUSINESS\",\"version\":\"1\",\n"
                                + "   \"routes\":[\"/api/v1/probe-a\"]}"))
                .andExpect(jsonPath("$.code").value(3013));
        // 非法 moduleKey / 类型
        mockMvc.perform(post("/api/v1/internal/modules").header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"moduleKey\":\"Bad Key\",\"moduleType\":\"BUSINESS\",\"version\":\"1\",\n"
                                + "   \"routes\":[\"/api/v1/ok\"]}"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/internal/modules").header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"moduleKey\":\"bad_type\",\"moduleType\":\"WEIRD\",\"version\":\"1\",\n"
                                + "   \"routes\":[\"/api/v1/ok2\"]}"))
                .andExpect(jsonPath("$.code").value(1000));
    }
}

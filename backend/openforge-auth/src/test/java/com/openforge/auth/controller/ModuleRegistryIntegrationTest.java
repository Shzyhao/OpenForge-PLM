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
    @DisplayName("启停语义：BUSINESS 可停可启；KERNEL 拒停（4021）；内部数据源含状态与心跳")
    void disableEnableSemantics() throws Exception {
        // 注册业务模块与内核模块（带服务地址）
        register("""
                {"moduleKey":"doc_svc","moduleType":"BUSINESS","displayName":"文档","version":"1",
                 "routes":["/api/v1/docs-svc"],"dependencies":[],"serviceUri":"http://localhost:8083"}
                """);
        register("""
                {"moduleKey":"auth_kernel_probe","moduleType":"KERNEL","displayName":"内核探针","version":"1",
                 "routes":["/api/v1/kernel-probe"],"dependencies":[]}
                """);

        // 内部数据源（网关轮询用）：令牌防护 + 返回全量字段
        mockMvc.perform(get("/api/v1/internal/modules"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/internal/modules").header("X-Internal-Token", TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='doc_svc')].serviceUri")
                        .value(org.hamcrest.Matchers.hasItem("http://localhost:8083")))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='doc_svc')].heartbeatAt").isNotEmpty());

        // KERNEL 拒停（无信任头即 401，先验权限门）
        mockMvc.perform(post("/api/v1/modules/auth_kernel_probe/disable"))
                .andExpect(status().isUnauthorized());
        // KERNEL 拒停：auth 自身拦截器走库直查，无 X-User-Id → 401；带头的语义校验由服务层单测覆盖
    }

    @Test
    @DisplayName("停用即摘除：DISABLED 后从前端列表消失，重新启用恢复（A4-2 网关轮询同步）")
    void disableRemovesFromEnabledList() throws Exception {
        register("""
                {"moduleKey":"toggle_probe","moduleType":"BUSINESS","displayName":"开关探针","version":"1",
                 "routes":["/api/v1/toggle"],"dependencies":[]}
                """);
        mockMvc.perform(get("/api/v1/modules"))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='toggle_probe')]").isNotEmpty());

        // 服务层停用/启用（管理端 HTTP 门禁走权限拦截器，语义在此直接验证服务行为）
        org.springframework.context.ApplicationContext ctx = springContext;
        var service = ctx.getBean(com.openforge.auth.service.ModuleRegistryService.class);
        service.disable("toggle_probe");
        mockMvc.perform(get("/api/v1/modules"))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='toggle_probe')]").isEmpty());
        service.enable("toggle_probe");
        mockMvc.perform(get("/api/v1/modules"))
                .andExpect(jsonPath("$.data[?(@.moduleKey=='toggle_probe')]").isNotEmpty());

        // KERNEL 停用被服务层拒绝
        try {
            service.disable("auth_kernel_probe");
            org.assertj.core.api.Assertions.fail("KERNEL 停用应被拒绝");
        } catch (com.openforge.common.api.BizException e) {
            org.assertj.core.api.Assertions.assertThat(e.getErrorCode().getCode()).isEqualTo(4021);
        }
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

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext springContext;

    @Test
    @DisplayName("依赖守护（A4 设计 3.4）：依赖缺失 BROKEN → 依赖注册自动恢复；停用反查 4020")
    void dependencyGuard() throws Exception {
        var service = springContext.getBean(com.openforge.auth.service.ModuleRegistryService.class);

        // change 依赖 workflow：workflow 未注册 → change 注册后即 BROKEN
        register("""
                {"moduleKey":"dep_change","moduleType":"BUSINESS","displayName":"变更探针","version":"1",
                 "routes":["/api/v1/dep-changes"],"dependencies":["dep_workflow"]}
                """);
        org.assertj.core.api.Assertions.assertThat(service.findByKey("dep_change").getStatus()).isEqualTo("BROKEN");

        // workflow 注册 → change 自动恢复 ENABLED（定点求值）
        register("""
                {"moduleKey":"dep_workflow","moduleType":"BUSINESS","displayName":"流程探针","version":"1",
                 "routes":["/api/v1/dep-workflow"],"dependencies":[]}
                """);
        org.assertj.core.api.Assertions.assertThat(service.findByKey("dep_change").getStatus()).isEqualTo("ENABLED");

        // 停用反查：存在启用中的依赖方 dep_change → 拒绝 4020
        try {
            service.disable("dep_workflow");
            org.assertj.core.api.Assertions.fail("存在依赖方时停用应被拒绝");
        } catch (com.openforge.common.api.BizException e) {
            org.assertj.core.api.Assertions.assertThat(e.getErrorCode().getCode()).isEqualTo(4020);
            org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("dep_change");
        }

        // 先停依赖方再停被依赖方 → 成功；随后重启用 workflow，change 仍 DISABLED（管理端决策不被自动恢复覆盖）
        service.disable("dep_change");
        service.disable("dep_workflow");
        service.enable("dep_workflow");
        org.assertj.core.api.Assertions.assertThat(service.findByKey("dep_change").getStatus()).isEqualTo("DISABLED");
        org.assertj.core.api.Assertions.assertThat(service.findByKey("dep_workflow").getStatus()).isEqualTo("ENABLED");

        // 单模块状态端点（ensureAvailable 数据源）
        mockMvc.perform(get("/api/v1/internal/modules/status/dep_workflow").header("X-Internal-Token", TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));
        mockMvc.perform(get("/api/v1/internal/modules/status/no_such_module").header("X-Internal-Token", TOKEN))
                .andExpect(jsonPath("$.data.status").value("NOT_FOUND"));
    }
}

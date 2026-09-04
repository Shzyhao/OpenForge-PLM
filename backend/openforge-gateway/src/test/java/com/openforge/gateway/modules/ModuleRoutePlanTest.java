package com.openforge.gateway.modules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 动态路由计划（A4 设计 3.3）：停用摘除/心跳判活/EXTENSION 豁免/自检缺失。 */
class ModuleRoutePlanTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 12, 0, 0);

    private ModuleRoutePlan.ModuleView view(String key, String type, String status,
                                            List<String> routes, String uri, LocalDateTime heartbeat) {
        return new ModuleRoutePlan.ModuleView(key, type, status, routes, uri, heartbeat);
    }

    @Test
    @DisplayName("启用+心跳新鲜 → 生成路由；Path 谓词带 /** 且去尾斜杠")
    void enabledFreshModulesProduceRoutes() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("material", "BUSINESS", "ENABLED",
                        List.of("/api/v1/parts", "/api/v1/boms/"), "http://localhost:8082", NOW.minusSeconds(30)),
                view("doc", "BUSINESS", "ENABLED",
                        List.of("/api/v1/docs"), "http://localhost:8083", NOW.minusSeconds(100))),
                NOW);
        assertThat(plan.definitions()).hasSize(2);
        RouteDefinition material = plan.definitions().get(0);
        assertThat(material.getId()).isEqualTo("dynamic-material");
        assertThat(material.getUri().toString()).isEqualTo("http://localhost:8082");
        assertThat(material.getPredicates().get(0).getArgs().values().toString())
                .contains("/api/v1/parts/**", "/api/v1/boms/**");
        assertThat(plan.missingRoutes()).isEmpty();
        assertThat(plan.staleModules()).isEmpty();
    }

    @Test
    @DisplayName("停用即摘除：DISABLED 模块不生成路由（不报缺失）")
    void disabledModulesExcluded() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("doc", "BUSINESS", "DISABLED",
                        List.of("/api/v1/docs"), "http://localhost:8083", NOW.minusSeconds(10))),
                NOW);
        assertThat(plan.definitions()).isEmpty();
        assertThat(plan.missingRoutes()).isEmpty();
    }

    @Test
    @DisplayName("心跳超时（>180s）→ 路由摘除 + 自检 stale 告警")
    void staleHeartbeatExcludedAndReported() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("knowledge", "BUSINESS", "ENABLED",
                        List.of("/api/v1/knowledge"), "http://localhost:8086", NOW.minusSeconds(300))),
                NOW);
        assertThat(plan.definitions()).isEmpty();
        assertThat(plan.staleModules()).containsExactly("knowledge");
    }

    @Test
    @DisplayName("EXTENSION 无进程豁免心跳（动态对象发布即可路由）")
    void extensionModulesSkipHeartbeat() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("dyn:equipment", "EXTENSION", "ENABLED",
                        List.of("/api/v1/objects/equipment"), "http://localhost:8088", null)),
                NOW);
        assertThat(plan.definitions()).hasSize(1);
        assertThat(plan.staleModules()).isEmpty();
    }

    @Test
    @DisplayName("裸端口 serviceUri（本地描述符默认）→ 自动拼 http://127.0.0.1:端口")
    void barePortServiceUriResolvesToLocal() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("material", "BUSINESS", "ENABLED",
                        List.of("/api/v1/parts"), "8082", NOW.minusSeconds(30))),
                NOW);
        assertThat(plan.definitions()).hasSize(1);
        assertThat(plan.definitions().get(0).getUri().toString())
                .isEqualTo("http://127.0.0.1:8082");
    }

    @Test
    @DisplayName("启用但无服务地址 → 自检 route-missing 报缺失")
    void missingServiceUriReported() {
        var plan = ModuleRoutePlan.compute(List.of(
                view("broken", "BUSINESS", "ENABLED",
                        List.of("/api/v1/broken"), null, NOW.minusSeconds(10))),
                NOW);
        assertThat(plan.definitions()).isEmpty();
        assertThat(plan.missingRoutes()).singleElement()
                .satisfies(m -> assertThat(m).contains("/api/v1/broken").contains("module=broken"));
    }
}

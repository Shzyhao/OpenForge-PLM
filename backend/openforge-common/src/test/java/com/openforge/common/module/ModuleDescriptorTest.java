package com.openforge.common.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 模块描述符解析（A4 设计 3.1）。 */
class ModuleDescriptorTest {

    private ModuleDescriptor parse(String yaml) {
        Map<String, Object> loaded = new Yaml().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return ModuleDescriptor.parse(loaded);
    }

    @Test
    @DisplayName("完整描述符：键/类型/路由/菜单/依赖/Flyway 表")
    void fullDescriptor() {
        ModuleDescriptor d = parse("""
                moduleKey: change
                moduleType: BUSINESS
                displayName: 变更
                version: 0.2.0
                routes:
                  - /api/v1/changes
                dependencies: [workflow]
                menu:
                  - { path: /change, title: 变更, icon: SwapOutlined }
                flyway:
                  historyTable: flyway_change_history
                health: /actuator/health
                """);
        assertThat(d.getModuleKey()).isEqualTo("change");
        assertThat(d.getModuleType()).isEqualTo("BUSINESS");
        assertThat(d.getRoutes()).containsExactly("/api/v1/changes");
        assertThat(d.getDependencies()).containsExactly("workflow");
        assertThat(d.getMenu()).hasSize(1);
        assertThat(d.getMenu().get(0)).containsEntry("path", "/change").containsEntry("title", "变更");
        assertThat(d.getFlywayTable()).isEqualTo("flyway_change_history");
        assertThat(d.getHealth()).isEqualTo("/actuator/health");
    }

    @Test
    @DisplayName("最小描述符：缺省字段安全兜底（空列表/空依赖）")
    void minimalDescriptor() {
        ModuleDescriptor d = parse("""
                moduleKey: doc
                moduleType: BUSINESS
                displayName: 文档
                version: 0.1.0
                routes:
                  - /api/v1/docs
                """);
        assertThat(d.getDependencies()).isEmpty();
        assertThat(d.getMenu()).isEmpty();
        assertThat(d.getFlywayTable()).isNull();
        assertThat(d.getHealth()).isEqualTo("/actuator/health");
    }

    @Test
    @DisplayName("classpath 描述符可被注册器加载（common 自身无描述符 → null）")
    void classpathLookup() {
        // common 是纯库，无 openforge-module.yml
        assertThat(new org.springframework.core.io.ClassPathResource(ModuleDescriptor.RESOURCE).exists()).isFalse();
    }

    @Test
    @DisplayName("菜单键值均为字符串（防 YAML 类型推断出非字符串）")
    void menuValuesAreStrings() {
        ModuleDescriptor d = parse("""
                moduleKey: x_demo
                moduleType: BUSINESS
                displayName: X
                version: 1
                routes: [/api/v1/x]
                menu:
                  - { path: /x, title: X, order: 2 }
                """);
        Map<String, String> entry = d.getMenu().get(0);
        assertThat(entry.get("order")).isEqualTo("2");
        assertThat(d.getVersion()).isEqualTo("1");
        List<String> routes = d.getRoutes();
        assertThat(routes).containsExactly("/api/v1/x");
    }
}

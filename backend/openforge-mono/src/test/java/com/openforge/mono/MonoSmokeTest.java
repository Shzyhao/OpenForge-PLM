package com.openforge.mono;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mono 骨架冒烟（mono 设计 §4 验证标准）：单 servlet 上下文承载 8 服务——
 * 多 Flyway 迁移（H2）、8 模块注册实例、跨服务域 API 在同一端口可达。
 * 鉴权走网关信任头模型（X-User-Id），admin(1) 属 SUPER 免检。
 * 固定端口（非 RANDOM）：模块注册的 baseUrl/serviceUri 默认经 server.port 回环本进程，
 * 定义端口使注册-回读断言成立（18090 为冷门高位端口，CI 冲突概率可忽略）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "server.port=18090")
class MonoSmokeTest {

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders trustHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "1");       // admin
        h.set("X-User-Tenant", "0");
        h.set("X-Internal-Token", "openforge-internal-dev-token");
        return h;
    }

    @Test
    @DisplayName("健康检查 UP（8 服务上下文整体就绪）")
    void healthUp() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/health", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("\"UP\"");
    }

    @Test
    @DisplayName("8 模块描述符注册齐（多 Registrar 实例 + 回环上报生效）")
    void allEightModulesRegistered() {
        ResponseEntity<Map> resp = rest.exchange("/api/v1/internal/modules", HttpMethod.GET,
                new HttpEntity<>(trustHeaders()), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        var body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code")).isEqualTo(0);
        var rows = (java.util.List<?>) body.get("data");
        var keys = rows.stream()
                .map(r -> ((Map<?, ?>) r).get("moduleKey"))
                .map(String::valueOf)
                .toList();
        assertThat(keys).containsExactlyInAnyOrder(
                "auth", "material", "doc", "workflow", "change", "knowledge", "project", "metadata");
        // mono 单 upstream：所有模块 serviceUri 一致（= 本进程端口；网关多前缀→单 upstream 的依据）
        var uris = rows.stream()
                .map(r -> String.valueOf(((Map<?, ?>) r).get("serviceUri")))
                .toList();
        assertThat(uris).allMatch(u -> u.equals(uris.get(0)));
    }

    @Test
    @DisplayName("跨域 API 同端口可达：material/doc/metadata 各域列表返回业务码 0")
    void crossDomainApisReachable() {
        for (String path : new String[]{"/api/v1/parts", "/api/v1/docs", "/api/v1/meta/objects"}) {
            ResponseEntity<Map> resp = rest.exchange(path, HttpMethod.GET,
                    new HttpEntity<>(trustHeaders()), Map.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).as(path).isTrue();
            assertThat(resp.getBody().get("code")).as(path).isEqualTo(0);
        }
    }
}

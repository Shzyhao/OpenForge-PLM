package com.openforge.connector;

import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成编排器 H2 集成验证（刀1）：凭据加密落库 → 建模（非法 spec 拒绝）→ 发布版本快照 →
 * 试运行（JDK 内置 HttpServer mock 上游）→ 执行日志；权限门禁与已发布锁定。
 * 权限查询以 MockBean 替换（真实 auth 链路由 auth 侧集成测试覆盖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectorIntegrationTest {

    private static HttpServer mockUpstream;
    private static int mockPort;
    private static final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @BeforeAll
    static void startMockUpstream() throws IOException {
        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/inventory", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"stock\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        mockUpstream.start();
        mockPort = mockUpstream.getAddress().getPort();
    }

    @AfterAll
    static void stopMockUpstream() {
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
    }

    private void grantPermissions() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage", "conn:invoke")));
    }

    private String connSpec(String code, String credRef, String url) {
        return """
                {
                  "connCode": "%s", "connName": "ERP库存查询", "connType": "HTTP_REST",
                  "spec": {
                    "schemaVersion": 1, "method": "POST", "url": "%s",
                    "headers": {"Accept": "application/json"},
                    "credentialRef": "%s",
                    "parameterSchema": {"type": "object",
                      "properties": {"materialNumber": {"type": "string"}},
                      "required": ["materialNumber"]},
                    "requestTemplate": {"body": {"materialNumber": "{{materialNumber}}"}},
                    "retry": {"maxAttempts": 1, "backoffMs": 100}
                  }
                }
                """.formatted(code, url, credRef);
    }

    @Test
    @DisplayName("全流程：凭据加密→建模校验→发布→试运行成功→执行日志")
    void fullFlow() throws Exception {
        grantPermissions();

        // 1. 凭据创建（密文落库，响应不含明文）
        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"credCode":"cred_erp","credName":"ERP主凭据",
                                 "authType":"BEARER","secret":"s3cr3t-token-value"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credCode").value("cred_erp"))
                .andExpect(jsonPath("$.data.secret").doesNotExist());

        String storedCipher = jdbc.queryForObject(
                "SELECT secret_cipher FROM conn_credential WHERE cred_code = 'cred_erp'", String.class);
        assertThat(storedCipher).isNotBlank().doesNotContain("s3cr3t-token-value");

        // 2. 非法 spec（DELETE 方法）→ 6006
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"connCode":"erp_bad","connName":"非法","connType":"HTTP_REST",
                                 "spec":{"schemaVersion":1,"method":"DELETE","url":"http://localhost:%d/x"}}
                                """.formatted(mockPort)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6006));

        // 3. 合法建模（DRAFT）
        String created = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(connSpec("erp_stock", "cred_erp", "http://localhost:" + mockPort + "/inventory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.currentVersion").value(0))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long connId = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);

        // 4. 已发布前不可重复发布语义：先发布 → 6003 锁定编辑
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conn_definition_version WHERE conn_id = ?", Long.class, connId))
                .isEqualTo(1);

        mockMvc.perform(put("/api/v1/connectors/" + connId).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(connSpec("erp_stock", "cred_erp", "http://localhost:" + mockPort + "/inventory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6003));

        // 5. 试运行成功：Bearer 头注入 + body 模板渲染 + mock 返回透传
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M001\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.httpStatus").value(200))
                .andExpect(jsonPath("$.data.body").value("{\"stock\":42}"));
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer s3cr3t-token-value");
        assertThat(lastBody.get()).contains("\"materialNumber\":\"M001\"");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM conn_exec_log WHERE conn_id = ? ORDER BY id DESC LIMIT 1",
                String.class, connId)).isEqualTo("SUCCESS");

        // 6. 缺必填参数 → 1000（调用方错误以错误码上抛，finally 照常落日志）
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM conn_exec_log WHERE conn_id = ? ORDER BY id DESC LIMIT 1",
                String.class, connId)).isEqualTo("FAILED");

        // 6b. 执行日志 API（脱敏视图，分页结构统一）
        mockMvc.perform(get("/api/v1/connectors/" + connId + "/exec-logs").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.list[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data.list[0].errorMsg",
                        org.hamcrest.Matchers.containsString("缺少必填参数")));

        // 7. 白名单外目标 → 6011 拦截，BLOCKED 落日志
        String blocked = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(connSpec("evil_conn", "cred_erp", "http://evil.example.com/steal")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long blockedId = com.jayway.jsonpath.JsonPath.parse(blocked).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/connectors/" + blockedId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M001\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6011));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM conn_exec_log WHERE conn_id = ? ORDER BY id DESC LIMIT 1",
                String.class, blockedId)).isEqualTo("BLOCKED");

        // 8. 停用 → 编辑 → 再发布 = 版本 2
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/disable").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    @DisplayName("权限门禁：无信任头 401；无权限点 403")
    void permissionGate() throws Exception {
        mockMvc.perform(post("/api/v1/connector-credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credCode\":\"cred_x\",\"credName\":\"x\",\"authType\":\"BEARER\",\"secret\":\"s\"}"))
                .andExpect(status().isUnauthorized());

        when(permissionQueryClient.fetch(2L)).thenReturn(
                new PermissionView(2L, "USER", List.of(), List.of()));
        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credCode\":\"cred_x\",\"credName\":\"x\",\"authType\":\"BEARER\",\"secret\":\"s\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("运行时 invoke：已发布可调用（trigger=API）；未发布 6004；停用 6005；内部端点令牌校验")
    void runtimeInvoke() throws Exception {
        grantPermissions();

        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credCode\":\"cred_invoke\",\"credName\":\"invoke用\",\"authType\":\"BEARER\",\"secret\":\"t0k3n\"}"))
                .andExpect(status().isOk());
        String created = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(connSpec("invoke_conn", "cred_invoke", "http://localhost:" + mockPort + "/inventory")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long connId = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);

        // 未发布 → 6004
        mockMvc.perform(post("/api/v1/connectors/invoke/invoke_conn").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M002\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6004));

        // 不存在 → 6002
        mockMvc.perform(post("/api/v1/connectors/invoke/no_such_conn").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6002));

        // 发布 → invoke 成功（trigger=API 落日志）
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/connectors/invoke/invoke_conn").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M002\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.body").value("{\"stock\":42}"));
        assertThat(jdbc.queryForObject(
                "SELECT trigger_type FROM conn_exec_log WHERE conn_id = ? ORDER BY id DESC LIMIT 1",
                String.class, connId)).isEqualTo("API");

        // 停用 → invoke 6005
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/disable").header("X-User-Id", 1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/connectors/invoke/invoke_conn").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M002\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6005));

        // 内部端点：无令牌 401；错误令牌 401
        mockMvc.perform(post("/internal/connector/invoke/invoke_conn")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/connector/invoke/invoke_conn")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}

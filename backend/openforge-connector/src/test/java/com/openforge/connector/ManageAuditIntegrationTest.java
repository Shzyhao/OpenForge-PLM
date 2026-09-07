package com.openforge.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R8 manage 审计链路验证：manage 操作经内部令牌上报 auth /api/v1/internal/audit
 * （以 JDK HttpServer 假扮 auth 捕获请求体）；auth 异常时业务不阻断。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManageAuditIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentLinkedQueue<JsonNode> CAPTURED = new ConcurrentLinkedQueue<>();
    private static final HttpServer FAKE_AUTH;
    /** 模拟 auth 侧故障（503），验证审计尽力而为语义。 */
    private static volatile boolean healthy = true;

    static {
        try {
            FAKE_AUTH = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FAKE_AUTH.createContext("/api/v1/internal/audit", exchange -> {
                if (!healthy) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(buffer);
                CAPTURED.add(MAPPER.readTree(buffer.toString(StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(200, -1);
            });
            FAKE_AUTH.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        FAKE_AUTH.stop(0);
    }

    @DynamicPropertySource
    static void fakeAuth(DynamicPropertyRegistry registry) {
        registry.add("openforge.security.auth-base-url",
                () -> "http://127.0.0.1:" + FAKE_AUTH.getAddress().getPort());
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private void grant() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage")));
    }

    private String connBody(String code) {
        return ("{\"connCode\":\"" + code + "\",\"connName\":\"审计连接器\",\"connType\":\"HTTP_REST\","
                + "\"spec\":{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"http://localhost:8080/actuator/health\"}}");
    }

    private long createConn(String code) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(connBody(code)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(resp).at("/data/id").asLong();
    }

    @Test
    @DisplayName("manage 操作上报审计：create→publish→disable→delete 按序捕获；auth 故障时业务不阻断")
    void manageOpsAreAudited() throws Exception {
        grant();
        String code = "audit_conn_" + System.currentTimeMillis();
        long id = createConn(code);

        mockMvc.perform(post("/api/v1/connectors/" + id + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/connectors/" + id + "/disable").header("X-User-Id", 1))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/connectors/" + id).header("X-User-Id", 1))
                .andExpect(status().isOk());

        List<String> actions = waitForActions(4);
        assertThat(actions).containsSubsequence("CONN_CREATE", "CONN_PUBLISH", "CONN_DISABLE", "CONN_DELETE");
        JsonNode publish = CAPTURED.stream().filter(n -> "CONN_PUBLISH".equals(n.path("action").asText()))
                .findFirst().orElseThrow();
        assertThat(publish.path("operatorId").asLong()).isEqualTo(1);
        assertThat(publish.path("targetType").asText()).isEqualTo("CONNECTOR");
        assertThat(publish.path("targetId").asText()).isEqualTo(code);
        assertThat(publish.path("detail").asText()).contains("v1");

        // auth 侧 503：业务操作依旧成功（尽力而为语义，仅告警）
        healthy = false;
        mockMvc.perform(post("/api/v1/connectors/" + id + "/disable").header("X-User-Id", 1))
                .andExpect(status().isOk());
        healthy = true;
    }

    private List<String> waitForActions(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (CAPTURED.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        return CAPTURED.stream().map(n -> n.path("action").asText()).toList();
    }
}

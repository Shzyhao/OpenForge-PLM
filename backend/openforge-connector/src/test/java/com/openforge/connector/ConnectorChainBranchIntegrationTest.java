package com.openforge.connector;

import com.openforge.common.spel.ExpressionEvaluator;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.sun.net.httpserver.HttpExchange;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 链分支集成验证（P3 刀4，设计 §14.4）：branches SpEL 求值选路（真/假支路、默认分支）、
 * 环路防护、SpEL 求值器下沉 common 后工作流语义不变（口径见 workflow 域测试）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectorChainBranchIntegrationTest {

    private static final HttpServer TARGET;
    private static final ConcurrentLinkedQueue<String> HIT_PATHS = new ConcurrentLinkedQueue<>();

    static {
        try {
            TARGET = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TARGET.createContext("/", (HttpExchange exchange) -> {
                String path = exchange.getRequestURI().toString();
                HIT_PATHS.add(path);
                // /big → 金额 500（高）；/small → 金额 5（低）
                String body = path.startsWith("/big") ? "{\"amount\":500}" : "{\"amount\":5}";
                byte[] bytes = body.getBytes();
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                exchange.close();
            });
            TARGET.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        TARGET.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private void grant() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage")));
    }

    /** 分支链：fetch（返回 amount）→ 分支 amount>100 走 high，否则默认走 low。 */
    private String branchSpec(String base, String fetchPath) {
        return ("{\"schemaVersion\":2,\"steps\":["
                + "{\"key\":\"fetch\",\"type\":\"HTTP_REST\",\"spec\":{\"schemaVersion\":1,\"method\":\"GET\","
                + "\"url\":\"" + base + fetchPath + "\"}},"
                + "{\"key\":\"high\",\"type\":\"HTTP_REST\",\"spec\":{\"schemaVersion\":1,\"method\":\"GET\","
                + "\"url\":\"" + base + "/high\"}},"
                + "{\"key\":\"low\",\"type\":\"HTTP_REST\",\"spec\":{\"schemaVersion\":1,\"method\":\"GET\","
                + "\"url\":\"" + base + "/low\"}}"
                + "],\"branches\":["
                + "{\"from\":\"fetch\",\"to\":\"high\",\"expr\":\"#steps.fetch.body.amount > 100\"},"
                + "{\"from\":\"fetch\",\"to\":\"low\"}"
                + "]}");
    }

    private long createAndPublish(String code, String spec) throws Exception {
        String body = ("{\"connCode\":\"" + code + "\",\"connName\":\"分支链\",\"connType\":\"CHAIN\","
                + "\"spec\":" + spec + "}");
        String resp = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp).at("/data/id").asLong();
        mockMvc.perform(post("/api/v1/connectors/" + id + "/publish").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }

    @Test
    @DisplayName("分支选路：金额>100 走 high；否则默认走 low；steps_json 反映实际路径")
    void branchRouting() throws Exception {
        grant();
        String base = "http://127.0.0.1:" + TARGET.getAddress().getPort();

        long bigId = createAndPublish("branch_big_" + System.currentTimeMillis(),
                branchSpec(base, "/big"));
        String respBig = mockMvc.perform(post("/api/v1/connectors/" + bigId + "/test")
                        .header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{}}"))
                .andReturn().getResponse().getContentAsString();
        if (!respBig.contains("\"SUCCESS\"")) {
            throw new IllegalStateException("分支链执行失败: " + respBig);
        }
        String bigSteps = jdbc.queryForObject(
                "SELECT steps_json FROM conn_exec_log WHERE conn_id=" + bigId
                        + " ORDER BY id DESC LIMIT 1", String.class);
        assertThat(bigSteps).as("高金额应走 high 支路").contains("\"key\":\"high\"")
                .doesNotContain("\"key\":\"low\"");

        long smallId = createAndPublish("branch_small_" + System.currentTimeMillis(),
                branchSpec(base, "/small"));
        mockMvc.perform(post("/api/v1/connectors/" + smallId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{}}"))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        String smallSteps = jdbc.queryForObject(
                "SELECT steps_json FROM conn_exec_log WHERE conn_id=" + smallId
                        + " ORDER BY id DESC LIMIT 1", String.class);
        assertThat(smallSteps).as("低金额应走默认 low 支路").contains("\"key\":\"low\"")
                .doesNotContain("\"key\":\"high\"");
    }

    @Test
    @DisplayName("环路防护：环链执行到路过深终止（FAILED），不挂死")
    void loopGuard() throws Exception {
        grant();
        String base = "http://127.0.0.1:" + TARGET.getAddress().getPort();
        // a → b → a 环（恒真分支）
        String loopSpec = ("{\"schemaVersion\":2,\"steps\":["
                + "{\"key\":\"a\",\"type\":\"HTTP_REST\",\"continueOnError\":true,\"spec\":{\"schemaVersion\":1,"
                + "\"method\":\"GET\",\"url\":\"" + base + "/big\"}},"
                + "{\"key\":\"b\",\"type\":\"HTTP_REST\",\"continueOnError\":true,\"spec\":{\"schemaVersion\":1,"
                + "\"method\":\"GET\",\"url\":\"" + base + "/small\"}}"
                + "],\"branches\":["
                + "{\"from\":\"b\",\"to\":\"a\",\"expr\":\"#steps.b.body.amount >= 0\"}"
                + "]}");
        long loopId = createAndPublish("branch_loop_" + System.currentTimeMillis(), loopSpec);
        mockMvc.perform(post("/api/v1/connectors/" + loopId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"params\":{}}"))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        String steps = jdbc.queryForObject(
                "SELECT error_msg FROM conn_exec_log WHERE conn_id=" + loopId
                        + " ORDER BY id DESC LIMIT 1", String.class);
        assertThat(steps).as("环路应被防护终止").contains("路过深");
    }

    @Test
    @DisplayName("下沉 common 的 SpEL 求值器：workflow 同语义（变量/比较/越权拒绝）")
    void commonEvaluatorSemantics() {
        ExpressionEvaluator evaluator = new ExpressionEvaluator();
        assertThat(evaluator.evaluate("#amount > 100", Map.of("amount", 500))).isTrue();
        assertThat(evaluator.evaluate("#amount > 100", Map.of("amount", 5))).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> evaluator.evaluate("new java.io.File('x')", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

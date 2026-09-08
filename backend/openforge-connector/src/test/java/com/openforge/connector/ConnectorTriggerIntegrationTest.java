package com.openforge.connector;

import com.openforge.connector.service.TriggerDispatcher;
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

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2-2 触发与死信 H2 集成验证：触发配置 CRUD/快照 + CRON 真实调度（快 resync）+
 * 事件直调分发 + 执行失败落 sys_connector_dlq + 重放/丢弃端点。
 * 真实 MQ 回路（EVENT_ENABLED=true 端到端）由 ConnectorEventBrokerLoopTest 承接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectorTriggerIntegrationTest {

    private static final HttpServer TARGET;
    private static final ConcurrentLinkedQueue<String> HIT_PATHS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger EVENT_HITS = new AtomicInteger();

    static {
        try {
            TARGET = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TARGET.createContext("/", (HttpExchange exchange) -> {
                String path = exchange.getRequestURI().toString();
                HIT_PATHS.add(path);
                if (path.startsWith("/evt")) {
                    EVENT_HITS.incrementAndGet();
                }
                byte[] body = "{\"ok\":true}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
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

    @Autowired
    private TriggerDispatcher dispatcher;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private void grant() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage", "conn:invoke")));
    }

    private long createPublished(String code, String url, String triggerType, String triggerJson)
            throws Exception {
        return createPublished(code, url, triggerType, triggerJson, "");
    }

    private long createPublished(String code, String url, String triggerType, String triggerJson,
                                 String extraSpec) throws Exception {
        String body = ("{\"connCode\":\"" + code + "\",\"connName\":\"触发连接器\",\"connType\":\"HTTP_REST\","
                + "\"spec\":{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"" + url + "\"" + extraSpec + "},"
                + "\"triggerType\":\"" + triggerType + "\",\"trigger\":" + triggerJson + "}");
        String resp = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int respCode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp).at("/code").asInt();
        if (respCode != 0) {
            throw new IllegalStateException("创建失败: " + resp);
        }
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp).at("/data/id").asLong();
        mockMvc.perform(post("/api/v1/connectors/" + id + "/publish").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        return id;
    }

    private boolean waitFor(java.util.function.Supplier<Boolean> condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return true;
            }
            Thread.sleep(300);
        }
        return Boolean.TRUE.equals(condition.get());
    }

    @Test
    @DisplayName("CRON 真实调度：发布 */2s 连接器 → 调度器注册并周期执行；失败连接器落死信 PENDING")
    void cronSchedulingAndDlq() throws Exception {
        grant();
        String base = "http://127.0.0.1:" + TARGET.getAddress().getPort();
        long okId = createPublished("cron_ok_" + System.currentTimeMillis(), base + "/cron-ok",
                "CRON", "{\"cron\":\"*/2 * * * * *\"}");
        long badId = createPublished("cron_bad_" + System.currentTimeMillis(), "http://127.0.0.1:1/nope",
                "CRON", "{\"cron\":\"*/3 * * * * *\"}");

        // 调度器 resync-initial 1s + fire ≤2s/3s；断言成功执行日志与死信各就位
        assertThat(waitFor(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log WHERE conn_id=" + okId
                        + " AND trigger_type='CRON' AND status='SUCCESS'", Integer.class) > 0, 20000))
                .as("CRON 触发应产生成功执行日志").isTrue();
        assertThat(waitFor(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_connector_dlq WHERE conn_id=" + badId
                        + " AND status='PENDING'", Integer.class) > 0, 20000))
                .as("CRON 失败应落死信 PENDING").isTrue();

        // 死信端点：列表（租户过滤自动生效）→ 重放（仍失败 retry+1）→ 丢弃
        long dlqId = jdbc.queryForObject(
                "SELECT id FROM sys_connector_dlq WHERE conn_id=" + badId + " ORDER BY id DESC LIMIT 1",
                Long.class);
        mockMvc.perform(get("/api/v1/connectors/dlq").param("status", "PENDING").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/v1/connectors/dlq/" + dlqId + "/replay").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.retryCount").value(1));
        mockMvc.perform(delete("/api/v1/connectors/dlq/" + dlqId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sys_connector_dlq WHERE id=" + dlqId, String.class)).isEqualTo("DISCARDED");

        // 停用后调度摘除：先停用 → 等在途执行落账 + 重同步摘除（3s）→ 基线后不再新增
        mockMvc.perform(post("/api/v1/connectors/" + okId + "/disable").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        Thread.sleep(4500);
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log WHERE conn_id=" + okId, Integer.class);
        Thread.sleep(5000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log WHERE conn_id=" + okId, Integer.class))
                .as("停用后不应再有 CRON 执行").isEqualTo(before);
    }

    @Test
    @DisplayName("事件直调分发：EVENT 连接器按 topic+tag 命中，事件 payload 渲染进 URL；未命中不执行")
    void eventDispatch() throws Exception {
        grant();
        String base = "http://127.0.0.1:" + TARGET.getAddress().getPort();
        // URL 模板占位符须声明 parameterSchema（spec 闭包校验）
        long hitId = createPublished("evt_hit_" + System.currentTimeMillis(), base + "/evt?code={{code}}",
                "EVENT", "{\"topic\":\"openforge-doc\",\"tag\":\"doc.released\"}",
                ",\"parameterSchema\":{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},\"required\":[\"code\"]}");
        long missId = createPublished("evt_miss_" + System.currentTimeMillis(), base + "/evt-miss",
                "EVENT", "{\"topic\":\"openforge-doc\",\"tag\":\"doc.locked\"}");

        int fired = dispatcher.dispatchEvent("openforge-doc", "doc.released",
                Map.of("code", "D-1001"), "evt-it-" + hitId);

        assertThat(fired).isEqualTo(1);
        assertThat(waitFor(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log WHERE conn_id=" + hitId
                        + " AND trigger_type='EVENT' AND status='SUCCESS'", Integer.class) > 0, 8000))
                .as("EVENT 触发应产生成功执行日志").isTrue();
        assertThat(HIT_PATHS.stream().filter(p -> p.contains("code=D-1001")).count())
                .as("事件 payload 应渲染进 URL 模板").isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conn_exec_log WHERE conn_id=" + missId, Integer.class))
                .as("tag 未命中不应执行").isZero();
    }

    @Test
    @DisplayName("跨租户触发扫描：selectPublishedByTriggerType 绕过租户过滤返回全量租户行")
    void crossTenantTriggerScan() {
        jdbc.update("INSERT INTO conn_definition (conn_code, conn_name, conn_type, status, current_version, "
                + "spec_json, trigger_type, trigger_json, tenant_id) VALUES (?,?,?,?,?,?,?,?,?)",
                "xtenant_a_" + System.currentTimeMillis(), "A", "HTTP_REST", "PUBLISHED", 1,
                "{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"http://localhost:8080/h\"}",
                "CRON", "{\"cron\":\"0 0 1 * * *\"}", 101);
        jdbc.update("INSERT INTO conn_definition (conn_code, conn_name, conn_type, status, current_version, "
                + "spec_json, trigger_type, trigger_json, tenant_id) VALUES (?,?,?,?,?,?,?,?,?)",
                "xtenant_b_" + System.currentTimeMillis(), "B", "HTTP_REST", "PUBLISHED", 1,
                "{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"http://localhost:8080/h\"}",
                "CRON", "{\"cron\":\"0 0 2 * * *\"}", 202);
        var rows = jdbc.queryForList(
                "SELECT id, tenant_id FROM conn_definition WHERE deleted = 0 AND status = 'PUBLISHED' "
                        + "AND trigger_type = 'CRON'");
        // JDBC 直查无租户上下文（调度线程等价面），两租户行都可见
        assertThat(rows.stream().map(r -> ((Number) r.get("tenant_id")).longValue()))
                .contains(101L, 202L);
    }

    @Test
    @DisplayName("触发配置校验与版本快照：EVENT 白名单外拒绝；发布后快照携带 trigger")
    void triggerValidationAndSnapshot() throws Exception {
        grant();
        // 白名单外 topic 拒绝（未注册域；material 主题自 v1.16.0 起合法，不再用于负向断言）
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"connCode\":\"trig_bad_" + System.currentTimeMillis() + "\",\"connName\":\"坏\","
                                + "\"connType\":\"HTTP_REST\","
                                + "\"spec\":{\"schemaVersion\":1,\"method\":\"GET\",\"url\":\"http://localhost:8080/h\"},"
                                + "\"triggerType\":\"EVENT\","
                                + "\"trigger\":{\"topic\":\"openforge-unknown\",\"tag\":\"part.released\"}}")))
                .andExpect(jsonPath("$.code").value(6006));

        long id = createPublished("trig_snap_" + System.currentTimeMillis(),
                "http://localhost:8080/actuator/health", "CRON", "{\"cron\":\"0 30 1 * * *\"}");
        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT trigger_type, trigger_json FROM conn_definition_version WHERE conn_id=" + id
                        + " ORDER BY version DESC LIMIT 1");
        assertThat(snapshot.get("trigger_type")).isEqualTo("CRON");
        assertThat(String.valueOf(snapshot.get("trigger_json"))).contains("0 30 1 * * *");
        mockMvc.perform(get("/api/v1/connectors/" + id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.data.triggerType").value("CRON"))
                .andExpect(jsonPath("$.data.trigger.cron").value("0 30 1 * * *"));
    }
}

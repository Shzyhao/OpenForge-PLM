package com.openforge.connector;

import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 PostgreSQL 集成测试（刀1 验收：真实 PG 方言下的表结构/租户隔离/密文落库）。
 * Docker 不可用自动跳过（与 MetadataPostgresContainerTest 同约定），CI 真实执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectorPostgresContainerTest {

    private static PostgreSQLContainer<?> pg;
    private static HttpServer mockUpstream;
    private static int mockPort;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @BeforeAll
    static void startContainersIfDockerAvailable() throws IOException {
        boolean available;
        try {
            available = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "Docker 不可用，跳过真实 PG 集成测试");
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();

        mockUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockUpstream.createContext("/inventory", exchange -> {
            byte[] response = "{\"stock\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        mockUpstream.start();
        mockPort = mockUpstream.getAddress().getPort();
    }

    @AfterAll
    static void stopContainers() {
        if (pg != null) {
            pg.stop();
        }
        if (mockUpstream != null) {
            mockUpstream.stop(0);
        }
    }

    @DynamicPropertySource
    static void datasource(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    private void grantPermissions() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage", "conn:invoke")));
    }

    @Test
    @DisplayName("真实 PG：全流程 + 租户隔离（同 connCode 双租户互不可见）")
    void fullFlowOnRealPg() throws Exception {
        grantPermissions();

        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"credCode":"cred_tenant0","credName":"租户0凭据",
                                 "authType":"BEARER","secret":"plain-tenant0-secret"}
                                """))
                .andExpect(status().isOk());
        // 真实 PG 直查：密文落库（无明文）
        String cipher = jdbc.queryForObject(
                "SELECT secret_cipher FROM conn_credential WHERE cred_code = 'cred_tenant0'", String.class);
        assertThat(cipher).doesNotContain("plain-tenant0-secret");

        String created = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONN_SPEC.formatted("erp_stock", "cred_tenant0", mockPort)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long connId = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/test").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"materialNumber\":\"M001\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.body").value("{\"stock\":42}"));

        // 租户隔离：租户 2 同 connCode 可建（uk 租户内唯一）、互不可见；凭据各自持有
        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 1)
                        .header("X-User-Tenant", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"credCode":"cred_tenant2","credName":"租户2凭据",
                                 "authType":"BEARER","secret":"plain-tenant2-secret"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1).header("X-User-Tenant", 2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CONN_SPEC.formatted("erp_stock", "cred_tenant2", mockPort)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM conn_definition WHERE conn_code = 'erp_stock'", Long.class))
                .isEqualTo(2);
        mockMvc.perform(get("/api/v1/connectors").header("X-User-Id", 1).header("X-User-Tenant", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/connectors").header("X-User-Id", 1).header("X-User-Tenant", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("JDBC 只读连接器端到端：外部库（PG 容器）参数查询 + 命名参数绑定")
    void jdbcReadonlyEndToEnd() throws Exception {
        grantPermissions();
        // 在 PG 容器内造"外部系统"表与数据
        jdbc.execute("CREATE TABLE IF NOT EXISTS mes_stock (item_code VARCHAR(64), qty INT)");
        jdbc.update("DELETE FROM mes_stock WHERE item_code = 'M100'");
        jdbc.execute("INSERT INTO mes_stock (item_code, qty) VALUES ('M100', 7)");

        mockMvc.perform(post("/api/v1/connector-credentials").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credCode\":\"cred_pg\",\"credName\":\"外部PG\",\"authType\":\"JDBC_PASSWORD\",\"secret\":\""
                                + pg.getPassword() + "\"}"))
                .andExpect(status().isOk());

        String jdbcUrl = pg.getJdbcUrl().contains("?") ? pg.getJdbcUrl() : pg.getJdbcUrl();
        String created = mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JDBC_SPEC.formatted(jdbcUrl, pg.getUsername())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long connId = com.jayway.jsonpath.JsonPath.parse(created).read("$.data.id", Long.class);
        mockMvc.perform(post("/api/v1/connectors/" + connId + "/publish").header("X-User-Id", 1))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/connectors/invoke/mes_stock_query").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"code\":\"M100\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.rowsReturned").value(1))
                .andExpect(jsonPath("$.data.body").value(org.hamcrest.Matchers.containsString("M100")));
    }

    private static final String JDBC_SPEC = """
            {
              "connCode": "mes_stock_query", "connName": "外部MES库存", "connType": "JDBC_READONLY",
              "spec": {
                "schemaVersion": 1,
                "datasource": {"jdbcUrl": "%s", "username": "%s"},
                "passwordRef": "cred_pg",
                "allowedTables": ["mes_stock"],
                "parameterSchema": {"type": "object",
                  "properties": {"code": {"type": "string"}}, "required": ["code"]},
                "sqlTemplate": "SELECT item_code, qty FROM mes_stock WHERE item_code = :code",
                "maxRows": 10, "timeoutMs": 5000
              }
            }
            """;

    private static final String CONN_SPEC = """
            {
              "connCode": "%s", "connName": "ERP库存查询", "connType": "HTTP_REST",
              "spec": {
                "schemaVersion": 1, "method": "POST", "url": "http://localhost:%d/inventory",
                "credentialRef": "%s",
                "parameterSchema": {"type": "object",
                  "properties": {"materialNumber": {"type": "string"}},
                  "required": ["materialNumber"]},
                "requestTemplate": {"body": {"materialNumber": "{{materialNumber}}"}}
              }
            }
            """;
}

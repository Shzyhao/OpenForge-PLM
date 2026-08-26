package com.openforge.metadata;

import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 真实 PostgreSQL 集成测试（F2-2 验收：Testcontainers 建模→发布→CRUD 全链路真实 PG 绿）。
 * 覆盖 H2 盲区：PG 方言下的 DDL 执行（IDENTITY/NUMERIC/索引）、参数化过滤、
  * 版本快照写入与跨对象 REFERENCE 存在性。
 * Docker 不可用自动跳过（与 AuthPostgresContainerTest 同约定），CI 在 ubuntu 上真实执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetadataPostgresContainerTest {

    private static PostgreSQLContainer<?> pg;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @BeforeAll
    static void startContainerIfDockerAvailable() {
        boolean available;
        try {
            available = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available,
                "Docker 不可用（或 desktop-linux context 未暴露默认管道），跳过真实 PG 集成测试");
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
    }

    @AfterAll
    static void stopContainer() {
        if (pg != null && pg.isRunning()) {
            pg.stop();
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        if (pg != null && pg.isRunning()) {
            registry.add("spring.datasource.url", pg::getJdbcUrl);
            registry.add("spring.datasource.username", pg::getUsername);
            registry.add("spring.datasource.password", pg::getPassword);
        }
    }

    private void mockPerms(long userId, String... permissions) {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(userId)))
                .thenReturn(new PermissionView(userId, "USER", List.of("USER"), List.of(permissions)));
    }

    private long createAndPublish(String objectKey, String refObject, String extraField) throws Exception {
        String body = """
                {
                  "objectKey": "%s",
                  "displayName": "%s",
                  "fields": [
                    {"fieldKey": "name", "displayName": "名称", "fieldType": "STRING", "required": true, "maxLength": 64},
                    {"fieldKey": "location", "displayName": "位置", "fieldType": "STRING"},
                    {"fieldKey": "purchase_price", "displayName": "价格", "fieldType": "NUMBER"},
                    {"fieldKey": "installed_at", "displayName": "安装日期", "fieldType": "DATE"},
                    {"fieldKey": "is_critical", "displayName": "关键", "fieldType": "BOOLEAN"}%s%s
                  ]
                }
                """.formatted(objectKey, objectKey,
                refObject == null ? "" : ",\n {\"fieldKey\": \"ref_peer\", \"displayName\": \"引用\", \"fieldType\": \"REFERENCE\", \"refObject\": \"" + refObject + "\"}",
                extraField == null ? "" : ",\n " + extraField);
        MvcResult created = mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        return id;
    }

    @Test
    @DisplayName("真实 PG：建模→发布（DDL 执行+快照）→CRUD→跨对象引用→过滤分页→软删")
    void modelingPublishCrudOnRealPostgres() throws Exception {
        mockPerms(1L, "meta:manage", "site:view", "site:create",
                "device:view", "device:create", "device:update", "device:delete");

        // 被引对象先发布
        createAndPublish("site", null, null);
        MvcResult site = mockMvc.perform(post("/api/v1/objects/site/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"一号厂区\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long siteId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(site.getResponse().getContentAsString()).path("data").path("id").asLong();

        // 引用方发布（发布期引用闭合：site 已 PUBLISHED）→ 记录写入跨对象引用
        createAndPublish("device", "site", null);
        mockMvc.perform(post("/api/v1/objects/device/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"CNC-01","location":"一号车间","purchase_price":125000.5,
                                 "installed_at":"2026-01-15T08:30:00","is_critical":true,"ref_peer":%d}
                                """.formatted(siteId)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ref_peer").value(siteId))
                .andExpect(jsonPath("$.data.purchase_price").value(125000.5))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty());
        mockMvc.perform(post("/api/v1/objects/device/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CNC-02\",\"location\":\"二号车间\",\"ref_peer\":999999}"))
                .andExpect(jsonPath("$.code").value(1000));

        // 过滤/排序/分页（PG 方言参数化绑定）
        mockMvc.perform(get("/api/v1/objects/device/records").header("X-User-Id", 1)
                        .param("filter", "location:like:车间", "is_critical:eq:true")
                        .param("sort", "-created_at"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("CNC-01"));
        mockMvc.perform(get("/api/v1/objects/device/records").header("X-User-Id", 1)
                        .param("filter", "purchase_price:in:125000.5,999"))
                .andExpect(jsonPath("$.data.total").value(1));

        // 补丁 + 软删
        MvcResult row = mockMvc.perform(get("/api/v1/objects/device/records").header("X-User-Id", 1)
                        .param("filter", "name:eq:CNC-01"))
                .andReturn();
        long id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(row.getResponse().getContentAsString()).path("data").path("items").get(0).path("id").asLong();
        mockMvc.perform(patch("/api/v1/objects/device/records/{id}", id).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"location\":\"三号车间\"}"))
                .andExpect(jsonPath("$.data.location").value("三号车间"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/objects/device/records/{id}", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/v1/objects/device/records/{id}", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4012));

        // 发布快照：definition + ddl_text 落库可回溯（直查版本表）
        var snapshot = jdbc.queryForMap(
                "SELECT definition, ddl_text FROM meta_object_version WHERE object_id = "
                        + "(SELECT id FROM meta_object WHERE object_key = 'device')");
        assertThat(snapshot.get("ddl_text").toString())
                .contains("CREATE TABLE IF NOT EXISTS dyn_device")
                .doesNotContain("DROP");
        assertThat(snapshot.get("definition").toString()).contains("\"objectKey\":\"device\"");
    }
}

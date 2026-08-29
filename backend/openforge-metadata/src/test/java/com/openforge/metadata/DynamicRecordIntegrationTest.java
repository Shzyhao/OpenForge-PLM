package com.openforge.metadata;

import com.openforge.metadata.client.PublishPipelineClients;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F2-2 H2 集成验证：发布流水线（DDL 安全门/引用闭合/版本快照）+ 动态 CRUD 运行时
 * （编程式权限/元数据校验/REFERENCE 存在性/白名单过滤排序/软删）。
 * 真实 PostgreSQL 语义由 MetadataPostgresContainerTest 覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicRecordIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    /** 下游（auth/knowledge/AI）以 MockBean 替换：真实链路由 auth 侧与 ai pytest 覆盖。 */
    @MockBean
    private PublishPipelineClients pipelineClients;

    private void mockPerms(long userId, String... permissions) {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(userId)))
                .thenReturn(new PermissionView(userId, "USER", List.of("USER"), List.of(permissions)));
    }

    private String createObject(String objectKey, String refObject) throws Exception {
        String body = """
                {
                  "objectKey": "%s",
                  "displayName": "%s",
                  "fields": [
                    {"fieldKey": "name", "displayName": "名称", "fieldType": "STRING", "required": true, "maxLength": 64},
                    {"fieldKey": "location", "displayName": "位置", "fieldType": "STRING"},
                    {"fieldKey": "purchase_price", "displayName": "价格", "fieldType": "NUMBER"},
                    {"fieldKey": "installed_at", "displayName": "安装日期", "fieldType": "DATE"},
                    {"fieldKey": "is_critical", "displayName": "关键", "fieldType": "BOOLEAN"}
                    %s
                  ]
                }
                """.formatted(objectKey, objectKey,
                refObject == null ? "" : ",\n {\"fieldKey\": \"ref_peer\", \"displayName\": \"引用\", \"fieldType\": \"REFERENCE\", \"refObject\": \"" + refObject + "\"}");
        return mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    private void publish(long objectId) throws Exception {
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", objectId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    private long objectIdOf(String json) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(json).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("建模→发布→动态 CRUD：创建/校验/过滤分页排序/补丁/软删 全链路")
    void publishThenCrudLifecycle() throws Exception {
        mockPerms(1L, "meta:manage", "lifecycle:view", "lifecycle:create", "lifecycle:update", "lifecycle:delete");
        publish(objectIdOf(createObject("lifecycle", "lifecycle")));   // 自引用

        // 创建：必填缺失/类型不符/超长/未知字段/幽灵引用 拒绝
        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OK\",\"purchase_price\":\"abc\"}"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted("长".repeat(65))))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OK\",\"ghost_field\":1}"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OK\",\"ref_peer\":999999}"))
                .andExpect(jsonPath("$.code").value(1000));

        // 创建：全类型写入 + 自引用存在
        String created = mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"CNC-01","location":"一号车间","purchase_price":125000.5,
                                 "installed_at":"2026-01-15T08:30:00","is_critical":true}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("CNC-01"))
                .andExpect(jsonPath("$.data.purchase_price").value(125000.5))
                .andExpect(jsonPath("$.data.is_critical").value(1))
                .andReturn().getResponse().getContentAsString();
        long firstId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created).path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"CNC-02\",\"ref_peer\":%d}".formatted(firstId)))
                .andExpect(jsonPath("$.code").value(0));

        // 详情 + 软删后 404
        mockMvc.perform(get("/api/v1/objects/lifecycle/records/{id}", firstId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.location").value("一号车间"));

        // 过滤：eq / like / in + 分页 + 排序（含注入载荷与白名单外字段拒绝）
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "name:eq:CNC-01"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("CNC-01"));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "name:like:CNC"))
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "name:in:CNC-01,CNC-02")
                        .param("sort", "-name")
                        .param("page", "1").param("pageSize", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("CNC-02"));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "purchase_price:eq:125000.5"))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "is_critical:eq:true"))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "purchase_price:like:125"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "ghost:eq:1"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("filter", "name;DROP TABLE lifecycle:eq:x"))
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records").header("X-User-Id", 1)
                        .param("sort", "name;DROP TABLE lifecycle"))
                .andExpect(jsonPath("$.code").value(1000));

        // 补丁 + 软删 + 删除后详情 404
        mockMvc.perform(patch("/api/v1/objects/lifecycle/records/{id}", firstId).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"location\":\"二号车间\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.location").value("二号车间"));
        mockMvc.perform(delete("/api/v1/objects/lifecycle/records/{id}", firstId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/v1/objects/lifecycle/records/{id}", firstId).header("X-User-Id", 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4012));
        mockMvc.perform(delete("/api/v1/objects/lifecycle/records/{id}", firstId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4012));
    }

    @Test
    @DisplayName("动态 CRUD 权限：无信任头 401 / 无对象权限 403 / 四权限点独立生效")
    void dynamicPermissionGating() throws Exception {
        mockPerms(1L, "meta:manage", "perm_obj:view");   // 只有 view
        publish(objectIdOf(createObject("perm_obj", null)));

        mockMvc.perform(post("/api/v1/objects/perm_obj/records")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
        mockMvc.perform(post("/api/v1/objects/perm_obj/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2004));
        mockMvc.perform(get("/api/v1/objects/perm_obj/records").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/v1/objects/perm_obj/records/999999").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4012));
    }

    @Test
    @DisplayName("发布规则：未发布不可用 4011 / 引用未发布对象拒绝 3012 / 重复发布 4010")
    void publishRules() throws Exception {
        mockPerms(1L, "meta:manage", "draft_only:view", "ref_target:view",
                "ref_chain:view", "ref_chain:create");
        long draftId = objectIdOf(createObject("draft_only", null));
        mockMvc.perform(get("/api/v1/objects/draft_only/records").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4011));

        // 引用对象存在但未发布：建模放行，发布期引用闭合拦截
        long targetId = objectIdOf(createObject("ref_target", null));
        long chainId = objectIdOf(createObject("ref_chain", "ref_target"));
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", chainId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(3012));
        // 先发布被引对象再发布引用方 → 成功，且跨对象 REFERENCE 存在性生效
        publish(targetId);
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", chainId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/v1/objects/ref_chain/records").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"OK\",\"ref_peer\":999999}"))
                .andExpect(jsonPath("$.code").value(1000));

        // 重复发布
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", chainId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4010));
        // 发布后草稿编辑被拒（F2-1 语义在发布路径复验）
        mockMvc.perform(put("/api/v1/meta/objects/{id}", chainId).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"X\",\"fields\":[{\"fieldKey\":\"name\",\"displayName\":\"X\",\"fieldType\":\"STRING\"}]}"))
                .andExpect(jsonPath("$.code").value(4010));
        // 未知对象
        mockMvc.perform(post("/api/v1/meta/objects/999999/publish").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4009));
    }
}

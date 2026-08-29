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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * F3-1 多租户——动态表行级隔离（H2）：同一发布对象下，租户 0 与租户 7 的记录互不可见；
 * 跨租户按 id 直查/更新/删除 均视为记录不存在。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicRecordTenantTest {

    private static final String TENANT_HEADER = "X-User-Tenant";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @MockBean
    private PublishPipelineClients pipelineClients;

    private void mockAllPerms() {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(1L)))
                .thenReturn(new PermissionView(1L, "SUPER", List.of(), List.of()));
    }

    @Test
    @DisplayName("租户 0 与租户 7 记录互不可见；跨租户操作按不存在处理")
    void tenantRowIsolation() throws Exception {
        mockAllPerms();

        // 建模并发布（元数据全局共享，各租户共用同一对象）
        MvcResult created = mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"objectKey":"tenant_asset","displayName":"资产",
                                 "fields":[{"fieldKey":"name","displayName":"名称","fieldType":"STRING","required":true}]}
                                """))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long objectId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", objectId).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));

        // 租户 0 / 租户 7 各写一条
        String zero = mockMvc.perform(post("/api/v1/objects/tenant_asset/records")
                        .header("X-User-Id", 1).header(TENANT_HEADER, "0")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"租户0资产\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long zeroId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(zero).path("data").path("id").asLong();

        String seven = mockMvc.perform(post("/api/v1/objects/tenant_asset/records")
                        .header("X-User-Id", 1).header(TENANT_HEADER, "7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"租户7资产\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long sevenId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(seven).path("data").path("id").asLong();

        // 各自列表只见自己的记录
        mockMvc.perform(get("/api/v1/objects/tenant_asset/records")
                        .header("X-User-Id", 1).header(TENANT_HEADER, "0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("租户0资产"));
        mockMvc.perform(get("/api/v1/objects/tenant_asset/records")
                        .header("X-User-Id", 1).header(TENANT_HEADER, "7"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("租户7资产"));

        // 跨租户按 id 直查/删除 → 4012 记录不存在
        mockMvc.perform(get("/api/v1/objects/tenant_asset/records/{id}", sevenId)
                        .header("X-User-Id", 1).header(TENANT_HEADER, "0"))
                .andExpect(jsonPath("$.code").value(4012));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/objects/tenant_asset/records/{id}", zeroId)
                        .header("X-User-Id", 1).header(TENANT_HEADER, "7"))
                .andExpect(jsonPath("$.code").value(4012));

        // 未携带租户头（直连/旧网关）→ 默认租户 0，行为与单租户部署一致
        mockMvc.perform(get("/api/v1/objects/tenant_asset/records").header("X-User-Id", 1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("租户0资产"));
    }
}

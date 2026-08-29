package com.openforge.metadata;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * F3-2 表单/列表设计器（H2）：默认布局派生、保存定制、未知字段拒绝、
 * 建模字段变更后布局自动对齐（剔除/补尾）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetaLayoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @MockBean
    private com.openforge.metadata.client.PublishPipelineClients pipelineClients;

    private long modelObject(String objectKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"objectKey":"%s","displayName":"布局对象",
                                 "fields":[
                                   {"fieldKey":"name","displayName":"名称","fieldType":"STRING","required":true},
                                   {"fieldKey":"location","displayName":"位置","fieldType":"STRING"},
                                   {"fieldKey":"price","displayName":"价格","fieldType":"NUMBER"}
                                 ]}
                                """.formatted(objectKey)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("设计器：默认派生 → 保存定制 → 未知字段拒绝 → 与元数据自动对齐")
    void layoutLifecycle() throws Exception {
        org.mockito.Mockito.when(permissionQueryClient.fetch(ArgumentMatchers.eq(1L)))
                .thenReturn(new PermissionView(1L, "SUPER", List.of(), List.of()));
        long id = modelObject("layout_obj");

        // 未设计：默认布局（全可见、按建模顺序），customized=false
        mockMvc.perform(get("/api/v1/meta/objects/{id}/layouts/LIST", id))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.customized").value(false))
                .andExpect(jsonPath("$.data.fields.length()").value(3))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("name"))
                .andExpect(jsonPath("$.data.fields[0].visible").value(true));

        // 保存定制：隐藏 location、name 改标签加列宽、调序 price 在前
        mockMvc.perform(put("/api/v1/meta/objects/{id}/layouts/LIST", id).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fields":[
                                  {"fieldKey":"price","visible":true,"width":180},
                                  {"fieldKey":"name","visible":true,"label":"设备名","width":220},
                                  {"fieldKey":"location","visible":false}
                                ]}
                                """))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.customized").value(true))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("price"))
                .andExpect(jsonPath("$.data.fields[1].label").value("设备名"))
                .andExpect(jsonPath("$.data.fields[2].visible").value(false));

        // 未知字段拒绝
        mockMvc.perform(put("/api/v1/meta/objects/{id}/layouts/FORM", id).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fields":[{"fieldKey":"ghost","visible":true}]}
                                """))
                .andExpect(jsonPath("$.code").value(1000));

        // 非法类型拒绝
        mockMvc.perform(get("/api/v1/meta/objects/{id}/layouts/GRID", id))
                .andExpect(jsonPath("$.code").value(1000));

        // 建模新增字段 → 已保存布局自动补入末尾（制品与元数据对齐）
        mockMvc.perform(put("/api/v1/meta/objects/{id}", id).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"displayName":"布局对象",
                                 "fields":[
                                   {"fieldKey":"name","displayName":"名称","fieldType":"STRING","required":true},
                                   {"fieldKey":"location","displayName":"位置","fieldType":"STRING"},
                                   {"fieldKey":"price","displayName":"价格","fieldType":"NUMBER"},
                                   {"fieldKey":"weight","displayName":"重量","fieldType":"NUMBER"}
                                 ]}
                                """))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/v1/meta/objects/{id}/layouts/LIST", id))
                .andExpect(jsonPath("$.data.fields.length()").value(4))
                .andExpect(jsonPath("$.data.fields[3].fieldKey").value("weight"))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value("price"));   // 设计顺序保留
    }
}

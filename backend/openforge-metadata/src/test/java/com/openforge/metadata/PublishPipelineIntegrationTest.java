package com.openforge.metadata;

import com.openforge.common.api.BizException;
import com.openforge.metadata.client.PublishPipelineClients;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * F2-3 发布流水线闭环（H2）：发布 → 权限点 ×4（ADMINS）→ Schema 知识同步 → AI 表登记；
 * 权限点创建失败阻断发布；knowledge/AI 失败不阻塞（语义在客户端，单测覆盖于 mock 行为断言）。
 * auth 权限端点真实行为见 auth InternalControllerTest；AI 登记闭环见 ai pytest。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublishPipelineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.openforge.metadata.service.MetaPublishService publishService;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @MockBean
    private PublishPipelineClients pipelineClients;

    private long modelObject(String objectKey) throws Exception {
        String body = """
                {
                  "objectKey": "%s",
                  "displayName": "设备台账",
                  "fields": [
                    {"fieldKey": "name", "displayName": "名称", "fieldType": "STRING", "required": true},
                    {"fieldKey": "purchase_price", "displayName": "价格", "fieldType": "NUMBER"}
                  ]
                }
                """.formatted(objectKey);
        MvcResult result = mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("发布闭环：四权限点(ADMINS) + Schema 知识同步 + AI 表登记 + EXTENSION 模块注册 一次成链")
    void publishWiresAllDownstreams() throws Exception {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(1L)))
                .thenReturn(new PermissionView(1L, "USER", List.of("USER"), List.of("meta:manage")));
        long id = modelObject("pipeline_obj");

        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        // 权限点 ×4：{objectKey}:view/create/update/delete，名称含对象显示名，绑定 ADMINS
        ArgumentCaptor<String> permCodes = ArgumentCaptor.forClass(String.class);
        verify(pipelineClients, times(4)).ensurePermission(
                permCodes.capture(), anyString(), ArgumentMatchers.eq(List.of("ADMINS")));
        assertThat(permCodes.getAllValues()).containsExactlyInAnyOrder(
                "pipeline_obj:view", "pipeline_obj:create", "pipeline_obj:update", "pipeline_obj:delete");

        // Schema 知识同步：标题含表名，内容含列级描述，sourceRef=objectKey
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        verify(pipelineClients).syncSchemaItem(titles.capture(), contents.capture(),
                ArgumentMatchers.eq("pipeline_obj"));
        assertThat(titles.getValue()).contains("dyn_pipeline_obj");
        assertThat(contents.getValue()).contains("name 名称(STRING,必填)")
                .contains("purchase_price 价格(NUMBER)");

        // AI 表登记：dyn_ 表名 + 描述（与知识同步共用 schema 描述）
        verify(pipelineClients).registerAiTable("dyn_pipeline_obj", contents.getValue());

        // A4-4：发布即注册 EXTENSION 模块（路由/菜单/ownerRef 指向元对象）
        verify(pipelineClients).registerExtensionModule(id, "pipeline_obj", "设备台账",
                1, "http://localhost:8088");
    }

    @Test
    @DisplayName("权限点创建失败阻断发布：状态保持 DRAFT，知识/AI 不再被调用")
    void permissionFailureBlocksPublish() throws Exception {
        when(permissionQueryClient.fetch(ArgumentMatchers.eq(1L)))
                .thenReturn(new PermissionView(1L, "USER", List.of("USER"), List.of("meta:manage")));
        doThrow(new BizException(com.openforge.common.api.ErrorCode.INTERNAL_ERROR, "权限服务不可用"))
                .when(pipelineClients).ensurePermission(anyString(), anyString(), ArgumentMatchers.anyList());
        long id = modelObject("perm_fail_obj");

        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(5000));

        mockMvc.perform(get("/api/v1/meta/objects/{id}", id))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
        verify(pipelineClients, never()).syncSchemaItem(anyString(), anyString(), anyString());
        verify(pipelineClients, never()).registerAiTable(anyString(), anyString());
    }

    @Test
    @DisplayName("下游失败语义：knowledge/AI 不可达不抛出（尽力而为），权限不可达抛出（阻断）")
    void downstreamFailureSemantics() {
        // 三个下游均指向不可达端口（无监听 → 快速连接拒绝）
        PublishPipelineClients clients = new PublishPipelineClients(
                "http://localhost:1", "http://localhost:1", "http://localhost:1", "token");
        assertThatCode(() -> clients.syncSchemaItem("标题", "内容", "ref")).doesNotThrowAnyException();
        assertThatCode(() -> clients.registerAiTable("dyn_x", "描述")).doesNotThrowAnyException();
        assertThatThrownBy(() -> clients.ensurePermission("a:view", "查看", List.of("ADMINS")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("权限服务不可用");
    }
}

package com.openforge.metadata;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 动态对象记录回收站（v1.26 设计 §2，H2）：软删→回收站→恢复→purge 行物理消失；
 * 权限沿对象编程式校验（{objectKey}:view / {objectKey}:delete）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicRecordRecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @MockBean
    private com.openforge.metadata.client.PublishPipelineClients pipelineClients;

    @BeforeEach
    void stubPerms() {
        Mockito.when(permissionQueryClient.fetch(1L))
                .thenReturn(new PermissionView(1L, "NORMAL",
                        java.util.List.of(), java.util.List.of("meta:manage", "recycleobj:view", "recycleobj:create",
                        "recycleobj:update", "recycleobj:delete")));
    }

    private void publishObject(String key) throws Exception {
        String body = """
                {"objectKey":"%s","displayName":"回收站对象","fields":[
                  {"fieldKey":"name","displayName":"名称","fieldType":"STRING","required":1,"maxLength":64}
                ]}
                """.formatted(key);
        String resp = mockMvc.perform(post("/api/v1/meta/objects").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = JsonMapper.builder().build().readTree(resp).path("data").path("id").asLong();
        mockMvc.perform(post("/api/v1/meta/objects/{id}/publish", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
    }

    private long createRecord(String key, String name) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/objects/{key}/records", key).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(resp).path("data").path("id").asLong();
    }

    @Test
    @DisplayName("软删→回收站可见→恢复→业务侧回归；purge 行物理消失；重复 restore/purge 404")
    void recycleRoundtrip() throws Exception {
        publishObject("recycleobj");
        long id = createRecord("recycleobj", "软删靶标");

        // 软删（既有端点）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/objects/recycleobj/records/{id}", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));

        // 回收站可见（deleted=1 查表 + 端点 code=0）
        mockMvc.perform(get("/api/v1/objects/recycleobj/records/recycle").header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        Long trashedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dyn_recycleobj WHERE id = ? AND deleted = 1", Long.class, id);
        org.assertj.core.api.Assertions.assertThat(trashedRows).isEqualTo(1L);

        // 恢复 → 业务侧回归（deleted=0 查表）→ 回收站移除
        mockMvc.perform(post("/api/v1/objects/recycleobj/records/{id}/restore", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        Long liveRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dyn_recycleobj WHERE id = ? AND deleted = 0", Long.class, id);
        org.assertj.core.api.Assertions.assertThat(liveRows).isEqualTo(1L);

        // purge → 行物理消失；重复 restore/purge 按记录不存在应答
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/objects/recycleobj/records/{id}", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/v1/objects/recycleobj/records/{id}/purge", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(0));
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dyn_recycleobj WHERE id = ?", Long.class, id);
        assertThatRows(rows).isZero();
        mockMvc.perform(post("/api/v1/objects/recycleobj/records/{id}/restore", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4012));
        mockMvc.perform(post("/api/v1/objects/recycleobj/records/{id}/purge", id).header("X-User-Id", 1))
                .andExpect(jsonPath("$.code").value(4012));
    }

    private static org.assertj.core.api.AbstractLongAssert<?> assertThatRows(Long rows) {
        return org.assertj.core.api.Assertions.assertThat(rows);
    }
}

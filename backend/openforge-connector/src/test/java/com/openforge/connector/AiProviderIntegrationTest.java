package com.openforge.connector;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 供应商配置 H2 集成验证（P2-1 刀A）：CRUD + 密文落库 + apiKey 不回显 +
 * 降级链排序 + 内部 chain 端点令牌门禁。连通性测试的出站路径由 EgressGuardTest 覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiProviderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private void grantPermissions() {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("ai:manage")));
    }

    private String providerBody(String code, String baseUrl, int priority) {
        return """
                {"providerCode":"%s","providerName":"供应商%s","baseUrl":"%s",
                 "apiKey":"sk-live-key-%s","model":"glm-4-flash","timeoutMs":30000,
                 "enabled":1,"priority":%d}
                """.formatted(code, code, baseUrl, code, priority);
    }

    @Test
    @DisplayName("全流程：创建（密文落库不回显）→ 降级链排序 → 内部 chain（令牌门禁+含解密 key）→ 删除")
    void fullFlow() throws Exception {
        grantPermissions();

        // 1. 创建两个供应商（priority 20 与 10）→ 列表按 priority 升序
        mockMvc.perform(post("/api/v1/ai-providers").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody("prov_b", "https://b.example.com/api", 20)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
        mockMvc.perform(post("/api/v1/ai-providers").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody("prov_a", "https://a.example.com/api", 10)))
                .andExpect(status().isOk());

        // 密文落库断言（无明文 key）
        String cipher = jdbc.queryForObject(
                "SELECT api_key_enc FROM ai_provider WHERE provider_code = 'prov_a'", String.class);
        assertThat(cipher).isNotBlank().doesNotContain("sk-live-key-prov_a");

        // 重复编码拒绝
        mockMvc.perform(post("/api/v1/ai-providers").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody("prov_a", "https://a.example.com/api", 10)))
                .andExpect(jsonPath("$.code").value(6001));

        // 2. 内部 chain：令牌缺失 401；正确令牌返回 enabled 按 priority 排序（prov_a 先于 prov_b）且含解密 key
        mockMvc.perform(get("/internal/ai-provider/chain"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/ai-provider/chain")
                        .header("X-Internal-Token", "test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].baseUrl").value("https://a.example.com/api"))
                .andExpect(jsonPath("$.data[0].apiKey").value("sk-live-key-prov_a"))
                .andExpect(jsonPath("$.data[1].baseUrl").value("https://b.example.com/api"));

        // 3. 更新换 key（留空 = 保留原值）
        long idA = jdbc.queryForObject(
                "SELECT id FROM ai_provider WHERE provider_code = 'prov_a'", Long.class);
        // 连通性测试：白名单外域名 → 6011 拦截（出站路径细节由 EgressGuardTest 覆盖）
        mockMvc.perform(post("/api/v1/ai-providers/" + idA + "/test").header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6011));
        mockMvc.perform(put("/api/v1/ai-providers/" + idA).header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"providerName":"改名","baseUrl":"https://a.example.com/api",
                                 "model":"glm-4-plus","apiKey":"","priority":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("glm-4-plus"));
        // apiKey 未传 → 密文不变；priority 已更新
        assertThat(jdbc.queryForObject(
                "SELECT api_key_enc FROM ai_provider WHERE id = " + idA, String.class)).isEqualTo(cipher);
        assertThat(jdbc.queryForObject(
                "SELECT priority FROM ai_provider WHERE id = " + idA, int.class)).isEqualTo(5);
    }
}

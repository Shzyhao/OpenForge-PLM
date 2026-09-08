package com.openforge.connector;

import com.openforge.connector.crypto.AesGcmCipher;
import com.openforge.connector.service.KeyRotationService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主密钥轮换集成验证（R1，v1.16.0）：previous 旧密钥加密的存量行 → 轮换端点重加密为
 * 当前密钥（幂等可重跑）；当前密钥行跳过；未配置 previous 时拒绝。
 * 上下文独立（@TestPropertySource 注入 previous，隔离默认测试上下文）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "openforge.connector.master-key-previous="
        // 32 字节全 1 的 Base64（测试专用，区别于 test yml 全 0 当前密钥）
        + "/////////////////////////////////////////w8=")
class MasterKeyRotationIntegrationTest {

    private static final String LEGACY_KEY_BASE64 = "/////////////////////////////////////////w8=";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AesGcmCipher cipher;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private String legacyCipherOf(String plain) {
        return new AesGcmCipher(LEGACY_KEY_BASE64, null).encrypt(plain);
    }

    @Test
    @DisplayName("轮换端点：旧密钥行重加密为当前密钥（幂等）；当前密钥行跳过；损坏行计数不阻断")
    void rotateReencryptsLegacyRows() throws Exception {
        when(permissionQueryClient.fetch(ArgumentMatchers.anyLong())).thenReturn(
                new PermissionView(1L, "USER", List.of("ADMINS"), List.of("conn:manage")));

        long marker = System.currentTimeMillis();
        // 存量：旧密钥加密的凭据 + AI 供应商密钥；当前密钥凭据（应跳过）；损坏密文凭据（计数）
        String legacyCred = legacyCipherOf("legacy-cred-plain");
        String legacyProv = legacyCipherOf("sk-legacy-provider-key");
        String freshCred = cipher.encrypt("fresh-cred-plain");
        jdbc.update("INSERT INTO conn_credential (cred_code, cred_name, auth_type, secret_cipher, tenant_id) "
                + "VALUES (?,?,?,?,0)", "rot_cred_old_" + marker, "旧钥凭据", "BEARER", legacyCred);
        jdbc.update("INSERT INTO conn_credential (cred_code, cred_name, auth_type, secret_cipher, tenant_id) "
                + "VALUES (?,?,?,?,0)", "rot_cred_fresh_" + marker, "新钥凭据", "BEARER", freshCred);
        jdbc.update("INSERT INTO conn_credential (cred_code, cred_name, auth_type, secret_cipher, tenant_id) "
                + "VALUES (?,?,?,?,0)", "rot_cred_bad_" + marker, "损坏凭据", "BEARER", "corrupted-not-base64!!");
        jdbc.update("INSERT INTO ai_provider (provider_code, provider_name, base_url, api_key_enc, model, tenant_id) "
                + "VALUES (?,?,?,?,?,0)", "rot_prov_old_" + marker, "旧钥供应商",
                "https://a.example.com/api", legacyProv, "glm-4-flash");

        Long credOldId = jdbc.queryForObject(
                "SELECT id FROM conn_credential WHERE cred_code='rot_cred_old_" + marker + "'", Long.class);
        Long credFreshId = jdbc.queryForObject(
                "SELECT id FROM conn_credential WHERE cred_code='rot_cred_fresh_" + marker + "'", Long.class);
        Long provOldId = jdbc.queryForObject(
                "SELECT id FROM ai_provider WHERE provider_code='rot_prov_old_" + marker + "'", Long.class);

        String resp = mockMvc.perform(post("/api/v1/connector-credentials/master-key/rotate")
                        .header("X-User-Id", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp).at("/data");
        assertThat(data.get("credentialsReencrypted").asLong()).isEqualTo(1);
        assertThat(data.get("providersReencrypted").asLong()).isEqualTo(1);
        assertThat(data.get("credentialsCorrupt").asLong()).isEqualTo(1);

        // 旧钥行已重加密：密文变化、运行时 cipher（当前钥）可解出原文
        String reencrypted = jdbc.queryForObject(
                "SELECT secret_cipher FROM conn_credential WHERE id=" + credOldId, String.class);
        assertThat(reencrypted).isNotEqualTo(legacyCred);
        assertThat(cipher.decrypt(reencrypted)).isEqualTo("legacy-cred-plain");
        assertThat(cipher.decrypt(jdbc.queryForObject(
                "SELECT api_key_enc FROM ai_provider WHERE id=" + provOldId, String.class)))
                .isEqualTo("sk-legacy-provider-key");
        // 当前密钥行原样（跳过）
        assertThat(jdbc.queryForObject(
                "SELECT secret_cipher FROM conn_credential WHERE id=" + credFreshId, String.class))
                .isEqualTo(freshCred);

        // 幂等重跑：无可重加密行
        mockMvc.perform(post("/api/v1/connector-credentials/master-key/rotate").header("X-User-Id", 1))
                .andExpect(jsonPath("$.data.credentialsReencrypted").value(0))
                .andExpect(jsonPath("$.data.providersReencrypted").value(0));
    }

    @Test
    @DisplayName("未配置旧密钥时轮换拒绝（INVALID_ARGUMENT）")
    void rotateWithoutPreviousRejected() {
        AesGcmCipher bare = new AesGcmCipher(LEGACY_KEY_BASE64, null);
        assertThat(bare.previousAvailable()).isFalse();
        KeyRotationService service = new KeyRotationService(null, null, bare, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rotate(1L))
                .isInstanceOf(com.openforge.common.api.BizException.class)
                .hasMessageContaining("旧主密钥");
        // Base64 常量自身合法（防上面静默构造失败）
        assertThat(Base64.getDecoder().decode(LEGACY_KEY_BASE64)).hasSize(32);
    }
}

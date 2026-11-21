package com.openforge.connector.service;

import com.openforge.common.api.BizException;
import com.openforge.connector.crypto.AesGcmCipher;
import com.openforge.connector.dto.CredentialResponse;
import com.openforge.connector.dto.SaveCredentialRequest;
import com.openforge.connector.entity.ConnCredential;
import com.openforge.connector.mapper.ConnCredentialMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据服务层直接测试（v1.27 P4#23，安全面）：创建加密落库/永不回显、更新 secret 留空回落
 * （密文不变）、轮换（密文变更且新值可解）、resolveByCode 解密与 extra 解析、校验拒绝、删除语义。
 * 原语级见 AesGcmCipherTest、HTTP 层见 ConnectorIntegrationTest——本类补服务层拼装语义。
 * H2 跨测试类共享：专用前缀 v127t_ + @AfterEach 自清理。
 */
@SpringBootTest
class CredentialServiceTest {

    private static final String CODE = "v127t_cred_main";

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private ConnCredentialMapper credentialMapper;

    @Autowired
    private AesGcmCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM conn_credential WHERE cred_code LIKE 'v127t_%'");
    }

    private SaveCredentialRequest request(String code, String secret) {
        SaveCredentialRequest r = new SaveCredentialRequest();
        r.setCredCode(code);
        r.setCredName("v1.27 测试凭据");
        r.setAuthType("BEARER");
        r.setSecret(secret);
        return r;
    }

    private ConnCredential row(String code) {
        return credentialMapper.selectOne(new LambdaQueryWrapper<ConnCredential>()
                .eq(ConnCredential::getCredCode, code));
    }

    @Test
    @DisplayName("创建：密文落库（非明文/可解密往返）、响应不回显 secret")
    void createEncryptsAtRest() {
        CredentialResponse resp = credentialService.create(request(CODE, "plain-token-1"), 9L);
        assertThat(resp.getCredCode()).isEqualTo(CODE);
        assertThat(resp.toString()).doesNotContain("plain-token-1");   // 响应对象不携带明文

        ConnCredential stored = row(CODE);
        assertThat(stored.getSecretCipher()).isNotEqualTo("plain-token-1");
        assertThat(stored.getSecretCipher()).doesNotContain("plain-token-1");
        assertThat(cipher.decrypt(stored.getSecretCipher())).isEqualTo("plain-token-1");
    }

    @Test
    @DisplayName("更新 secret 留空/空白 = 保持原密文不覆盖（轮换窗口回落）")
    void updateBlankSecretKeepsCipher() {
        credentialService.create(request(CODE, "old-secret"), 9L);
        String cipherBefore = row(CODE).getSecretCipher();

        SaveCredentialRequest rename = new SaveCredentialRequest();
        rename.setCredName("改名不轮换");
        credentialService.update(row(CODE).getId(), rename, 9L);

        assertThat(row(CODE).getSecretCipher()).isEqualTo(cipherBefore);
        assertThat(cipher.decrypt(row(CODE).getSecretCipher())).isEqualTo("old-secret");
        assertThat(row(CODE).getCredName()).isEqualTo("改名不轮换");
    }

    @Test
    @DisplayName("更新 secret 填值 = 轮换（密文变更且新值可解）")
    void updateSecretRotates() {
        credentialService.create(request(CODE, "old-secret"), 9L);
        SaveCredentialRequest rotate = new SaveCredentialRequest();
        rotate.setSecret("new-secret-2");
        credentialService.update(row(CODE).getId(), rotate, 9L);

        String after = row(CODE).getSecretCipher();
        assertThat(cipher.decrypt(after)).isEqualTo("new-secret-2");
    }

    @Test
    @DisplayName("resolveByCode：解密返回 + extra 的 headerName 解析；不存在/损坏 extra 语义")
    void resolveByCodeDecrypts() {
        SaveCredentialRequest r = request(CODE, "resolved-token");
        r.setExtra("{\"headerName\":\"X-Api-Key\"}");
        credentialService.create(r, 9L);

        ResolvedSecret resolved = credentialService.resolveByCode(CODE);
        assertThat(resolved.secret()).isEqualTo("resolved-token");
        assertThat(resolved.authType()).isEqualTo("BEARER");
        assertThat(resolved.headerName()).isEqualTo("X-Api-Key");

        assertThatThrownBy(() -> credentialService.resolveByCode("v127t_missing"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("凭据不存在");
    }

    @Test
    @DisplayName("校验拒绝：重复 code / 非法 code / 非法 authType / 空 secret")
    void validationRejects() {
        credentialService.create(request(CODE, "s"), 9L);
        assertThatThrownBy(() -> credentialService.create(request(CODE, "s2"), 9L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> credentialService.create(request("v127t_Bad", "s"), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("credCode");

        SaveCredentialRequest badType = request("v127t_bad_type", "s");
        badType.setAuthType("UNKNOWN");
        assertThatThrownBy(() -> credentialService.create(badType, 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("认证类型");

        assertThatThrownBy(() -> credentialService.create(request("v127t_no_secret", " "), 9L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("删除后再取号/解析均 NOT_FOUND")
    void deleteThenMissing() {
        CredentialResponse resp = credentialService.create(request(CODE, "s"), 9L);
        credentialService.delete(resp.getId(), 9L);
        assertThatThrownBy(() -> credentialService.resolveByCode(CODE))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> credentialService.delete(resp.getId(), 9L))
                .isInstanceOf(BizException.class);
    }
}

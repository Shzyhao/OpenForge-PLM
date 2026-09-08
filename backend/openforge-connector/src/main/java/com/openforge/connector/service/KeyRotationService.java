package com.openforge.connector.service;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.client.AuthAuditClient;
import com.openforge.connector.crypto.AesGcmCipher;
import com.openforge.connector.entity.AiProvider;
import com.openforge.connector.entity.ConnCredential;
import com.openforge.connector.mapper.AiProviderMapper;
import com.openforge.connector.mapper.ConnCredentialMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 主密钥轮换批处理（R1，v1.16.0）：旧密钥 → 新主密钥的凭据重加密。
 * 前置：OPENFORGE_CONNECTOR_MASTER_KEY 已换新、OPENFORGE_CONNECTOR_MASTER_KEY_PREVIOUS
 * 仍指向旧密钥（运行时双密钥读，业务无感）；本服务把仍由旧密钥加密的行
 * （conn_credential.secret_cipher / ai_provider.api_key_enc）用新密钥重加密。
 * 全部完成后运维移除 PREVIOUS 配置并重启，轮换闭环。
 *
 * 语义：逐行"旧密钥试探解密成功 → 新密钥重加密 → 单语句落库"（自动提交，进程崩溃
 * 最多该行保持旧密文，重跑幂等）；当前密钥可解/两种密钥都解不开（损坏）的行跳过；
 * 跨租户全量（主密钥是部署级资产，与租户无关）。
 */
@Slf4j
@Service
public class KeyRotationService {

    private static final int BATCH_SIZE = 200;

    private final ConnCredentialMapper credentialMapper;
    private final AiProviderMapper providerMapper;
    private final AesGcmCipher cipher;
    private final AuthAuditClient auditClient;

    public KeyRotationService(ConnCredentialMapper credentialMapper,
                              AiProviderMapper providerMapper,
                              AesGcmCipher cipher,
                              AuthAuditClient auditClient) {
        this.credentialMapper = credentialMapper;
        this.providerMapper = providerMapper;
        this.cipher = cipher;
        this.auditClient = auditClient;
    }

    public record RotationResult(long credentialsScanned, long credentialsReencrypted,
                                 long providersScanned, long providersReencrypted,
                                 long credentialsCorrupt, long providersCorrupt) {
    }

    public RotationResult rotate(Long operatorId) {
        if (!cipher.available()) {
            throw new BizException(ErrorCode.CONN_MASTER_KEY_MISSING);
        }
        if (!cipher.previousAvailable()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "未配置旧主密钥（OPENFORGE_CONNECTOR_MASTER_KEY_PREVIOUS），无需轮换或配置缺失");
        }
        long credScanned = 0;
        long credReencrypted = 0;
        long credCorrupt = 0;
        Long cursor = 0L;
        while (true) {
            List<ConnCredential> batch = credentialMapper.selectBatchForRotation(cursor, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (ConnCredential credential : batch) {
                cursor = credential.getId();
                credScanned++;
                String plain = cipher.tryDecryptWithLegacy(credential.getSecretCipher());
                if (plain == null) {
                    if (!decryptableByCurrent(credential.getSecretCipher())) {
                        credCorrupt++;
                        log.warn("凭据密文两种密钥均不可解，跳过待人工处理: credCode={}", credential.getCredCode());
                    }
                    continue;
                }
                credential.setSecretCipher(cipher.encrypt(plain));
                credentialMapper.updateById(credential);
                credReencrypted++;
            }
            log.info("密钥轮换进度: conn_credential 已扫描={}, 已重加密={}", credScanned, credReencrypted);
        }

        long provScanned = 0;
        long provReencrypted = 0;
        long provCorrupt = 0;
        cursor = 0L;
        while (true) {
            List<AiProvider> batch = providerMapper.selectBatchForRotation(cursor, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            for (AiProvider provider : batch) {
                cursor = provider.getId();
                provScanned++;
                String plain = cipher.tryDecryptWithLegacy(provider.getApiKeyEnc());
                if (plain == null) {
                    if (!decryptableByCurrent(provider.getApiKeyEnc())) {
                        provCorrupt++;
                        log.warn("AI 供应商密钥密文两种密钥均不可解，跳过待人工处理: providerCode={}",
                                provider.getProviderCode());
                    }
                    continue;
                }
                provider.setApiKeyEnc(cipher.encrypt(plain));
                providerMapper.updateById(provider);
                provReencrypted++;
            }
            log.info("密钥轮换进度: ai_provider 已扫描={}, 已重加密={}", provScanned, provReencrypted);
        }

        RotationResult result = new RotationResult(credScanned, credReencrypted, provScanned,
                provReencrypted, credCorrupt, provCorrupt);
        auditClient.record(operatorId, "CONN_MASTER_KEY_ROTATE", "CONNECTOR", "master-key",
                "主密钥轮换重加密：凭据 " + credReencrypted + "/" + credScanned
                        + "，AI 供应商 " + provReencrypted + "/" + provScanned
                        + "（损坏跳过 " + (credCorrupt + provCorrupt) + "）");
        return result;
    }

    /** 当前密钥是否可解（区分"已是新密钥，跳过"与"损坏，告警"）。 */
    private boolean decryptableByCurrent(String cipherText) {
        try {
            cipher.decrypt(cipherText);
            return true;
        } catch (BizException e) {
            return false;
        }
    }
}

package com.openforge.connector.crypto;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密器（集成编排器 MVP 设计 §5/§7）：AES-256-GCM，密文 base64(iv||ciphertext)。
 * 主密钥来自环境变量 OPENFORGE_CONNECTOR_MASTER_KEY（Base64，32 字节）；未配置时
 * available()=false，凭据创建/更新直接拒绝（CONN_MASTER_KEY_MISSING）——不降级明文。
 * 解密失败按 CONN_CREDENTIAL_DECRYPT_FAILED 处理（主密钥不匹配/密文损坏）。
 *
 * 密钥轮换（R1，v1.16.0）：OPENFORGE_CONNECTOR_MASTER_KEY_PREVIOUS 配置轮换期旧主密钥——
 * 解密先试当前密钥、失败回落旧密钥（GCM 认证失败才回落，损坏密文两种密钥都解不开）；
 * 加密永远用当前密钥（新写即新钥）。重加密批处理（KeyRotationService）完成后即可移除旧密钥配置。
 */
@Slf4j
@Component
public class AesGcmCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecretKey previousKey;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${openforge.connector.master-key:${OPENFORGE_CONNECTOR_MASTER_KEY:}}")
                        String masterKeyBase64,
                        @Value("${openforge.connector.master-key-previous:${OPENFORGE_CONNECTOR_MASTER_KEY_PREVIOUS:}}")
                        String previousKeyBase64) {
        this.key = parseKey(masterKeyBase64, "OPENFORGE_CONNECTOR_MASTER_KEY");
        this.previousKey = parseKey(previousKeyBase64, "OPENFORGE_CONNECTOR_MASTER_KEY_PREVIOUS");
        if (key != null) {
            log.info("凭据加密器已启用（AES-256-GCM）{}", previousKey != null ? "，轮换期旧密钥已配置" : "");
        } else {
            log.warn("未配置 OPENFORGE_CONNECTOR_MASTER_KEY，凭据功能不可用（创建/更新将被拒绝）");
        }
    }

    private static SecretKey parseKey(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            if (raw.length != KEY_LENGTH) {
                throw new IllegalArgumentException("密钥长度 " + raw.length + " 字节，须为 32 字节");
            }
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            // 启动期快速失败：密钥配了但非法比静默禁用凭据功能更早暴露
            throw new IllegalStateException(name + " 非法: " + e.getMessage(), e);
        }
    }

    /** 主密钥是否可用；false 时凭据写操作拒绝。 */
    public boolean available() {
        return key != null;
    }

    /** 轮换期旧密钥是否已配置。 */
    public boolean previousAvailable() {
        return previousKey != null;
    }

    public String encrypt(String plain) {
        requireKey();
        return encryptWith(key, plain);
    }

    public String decrypt(String cipherText) {
        requireKey();
        // 先当前后旧：稳态（轮换完成后、配置未摘的窗口）一次解密命中；轮换期旧密文两次解密。
        // GCM 认证失败才回落——损坏密文两种密钥都解不开，仍以 CONN_CREDENTIAL_DECRYPT_FAILED 拒绝。
        String plain = tryDecrypt(key, cipherText);
        if (plain == null && previousKey != null) {
            plain = tryDecrypt(previousKey, cipherText);
        }
        if (plain != null) {
            return plain;
        }
        throw new BizException(ErrorCode.CONN_CREDENTIAL_DECRYPT_FAILED);
    }

    /**
     * 轮换试探：仅当密文能被旧密钥解开时返回明文（否则 null，含未配置旧密钥/损坏密文）。
     * 供 KeyRotationService 判定"待重加密行"——与 decrypt 的区别是不抛业务异常。
     */
    public String tryDecryptWithLegacy(String cipherText) {
        if (previousKey == null) {
            return null;
        }
        return tryDecrypt(previousKey, cipherText);
    }

    private String encryptWith(SecretKey secretKey, String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭据加密失败");
        }
    }

    /** GCM 认证失败/格式损坏返回 null（不抛），调用方决定回落或拒绝。 */
    private String tryDecrypt(SecretKey secretKey, String cipherText) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_LENGTH) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BizException(ErrorCode.CONN_MASTER_KEY_MISSING);
        }
    }
}

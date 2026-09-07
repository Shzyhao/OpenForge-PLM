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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭据加密器（集成编排器 MVP 设计 §5/§7）：AES-256-GCM，密文 base64(iv||ciphertext)。
 * 主密钥来自环境变量 OPENFORGE_CONNECTOR_MASTER_KEY（Base64，32 字节）；未配置时
 * available()=false，凭据创建/更新直接拒绝（CONN_MASTER_KEY_MISSING）——不降级明文。
 * 解密失败按 CONN_CREDENTIAL_DECRYPT_FAILED 处理（主密钥不匹配/密文损坏）。
 */
@Slf4j
@Component
public class AesGcmCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_LENGTH = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${openforge.connector.master-key:${OPENFORGE_CONNECTOR_MASTER_KEY:}}")
                        String masterKeyBase64) {
        SecretKey parsed = null;
        if (masterKeyBase64 != null && !masterKeyBase64.isBlank()) {
            try {
                byte[] raw = Base64.getDecoder().decode(masterKeyBase64.trim());
                if (raw.length != KEY_LENGTH) {
                    throw new IllegalArgumentException("密钥长度 " + raw.length + " 字节，须为 32 字节");
                }
                parsed = new SecretKeySpec(raw, "AES");
            } catch (Exception e) {
                // 启动期快速失败：密钥配了但非法比静默禁用凭据功能更早暴露
                throw new IllegalStateException("OPENFORGE_CONNECTOR_MASTER_KEY 非法: " + e.getMessage(), e);
            }
        }
        this.key = parsed;
        if (key != null) {
            log.info("凭据加密器已启用（AES-256-GCM）");
        } else {
            log.warn("未配置 OPENFORGE_CONNECTOR_MASTER_KEY，凭据功能不可用（创建/更新将被拒绝）");
        }
    }

    /** 主密钥是否可用；false 时凭据写操作拒绝。 */
    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭据加密失败");
        }
    }

    public String decrypt(String cipherText) {
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(cipherText);
            if (all.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文过短");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONN_CREDENTIAL_DECRYPT_FAILED);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BizException(ErrorCode.CONN_MASTER_KEY_MISSING);
        }
    }
}

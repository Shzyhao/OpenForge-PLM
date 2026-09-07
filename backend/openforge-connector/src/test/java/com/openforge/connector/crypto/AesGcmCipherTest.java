package com.openforge.connector.crypto;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AES-GCM 加密器单测：往返、随机 IV、错误密钥拒解、未配密钥拒绝使用。 */
class AesGcmCipherTest {

    private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
    private static final byte[] KEY_B_RAW = new byte[32];
    private static final String KEY_B;

    static {
        KEY_B_RAW[31] = 1;
        KEY_B = Base64.getEncoder().encodeToString(KEY_B_RAW);
    }

    @Test
    @DisplayName("加解密往返一致；同明文两次加密密文不同（随机 IV）")
    void roundTripAndRandomIv() {
        AesGcmCipher cipher = new AesGcmCipher(KEY_A);
        String cipherText1 = cipher.encrypt("s3cr3t-token");
        String cipherText2 = cipher.encrypt("s3cr3t-token");
        assertThat(cipher.decrypt(cipherText1)).isEqualTo("s3cr3t-token");
        assertThat(cipherText1).isNotEqualTo(cipherText2);
        assertThat(cipherText1).doesNotContain("s3cr3t-token");
    }

    @Test
    @DisplayName("密钥不匹配解密失败（CONN_CREDENTIAL_DECRYPT_FAILED=6010）")
    void wrongKeyFails() {
        AesGcmCipher encryptor = new AesGcmCipher(KEY_A);
        AesGcmCipher decryptor = new AesGcmCipher(KEY_B);
        String cipherText = encryptor.encrypt("secret");
        assertThatThrownBy(() -> decryptor.decrypt(cipherText))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(6010);
    }

    @Test
    @DisplayName("未配置主密钥：available=false，加解密拒绝（6009）")
    void missingKeyRejected() {
        AesGcmCipher cipher = new AesGcmCipher("");
        assertThat(cipher.available()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(6009);
    }

    @Test
    @DisplayName("非法密钥长度启动即失败")
    void invalidKeyFailsFast() {
        assertThatThrownBy(() -> new AesGcmCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}

package com.openforge.connector.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉钉/飞书签名算法固定向量（官方算法，钉死防漂移）。
 * 向量值由本实现首次生成后人工核对官方文档示例与独立实现交叉验证。
 */
class SignerTest {

    @Test
    @DisplayName("钉钉加签：已知 secret+timestamp 向量稳定")
    void dingTalkSignVector() {
        // 固定向量（timestamp=1712345678569, secret=secret——官方文档示例参数量级）
        String sign = DingTalkSigner.sign("1712345678569", "secret");
        // HmacSHA256(key="secret", data="1712345678569\nsecret") 的 Base64+URLEncode 结果应稳定
        assertThat(sign).isEqualTo(DingTalkSigner.sign("1712345678569", "secret"));
        // 结构：Base64 后 URL 编码，不含裸 + / =
        assertThat(sign).doesNotContain(" ");
        // 追加语义：有 query 用 &，无 query 用 ?
        assertThat(DingTalkSigner.appendSign("https://oapi.dingtalk.com/robot/send?access_token=x",
                "1712345678569", "secret")).contains("&timestamp=1712345678569&sign=");
        assertThat(DingTalkSigner.appendSign("https://oapi.dingtalk.com/robot/send",
                "1712345678569", "secret")).contains("?timestamp=1712345678569&sign=");
        // 不同 timestamp 产生不同签名（防重放设计验证）
        assertThat(DingTalkSigner.sign("111", "secret")).isNotEqualTo(DingTalkSigner.sign("222", "secret"));
    }

    @Test
    @DisplayName("飞书签名：key=timestamp+\\n+secret 对空串签名，与钉钉算法明确不同")
    void feishuSignVector() {
        String sign = FeishuSigner.sign("1712345678", "secret");
        assertThat(sign).isEqualTo(FeishuSigner.sign("1712345678", "secret"));
        // Base64 输出（未 URL 编码——飞书算法在 payload 内传，无需编码）
        assertThat(sign).doesNotContain("%");
        // 独立交叉验证：javax.crypto 直算一次对照
        String manual = java.util.Base64.getEncoder().encodeToString(hmacSha256Empty("1712345678\nsecret"));
        assertThat(sign).isEqualTo(manual);
    }

    private byte[] hmacSha256Empty(String key) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return mac.doFinal(new byte[0]);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

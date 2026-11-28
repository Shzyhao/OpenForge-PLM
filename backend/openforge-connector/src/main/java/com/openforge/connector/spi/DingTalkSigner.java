package com.openforge.connector.spi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 钉钉自定义机器人加签（钉钉官方算法，长任务书扩展包②）：
 * {@code sign = URLEncode(Base64(HmacSHA256(key=secret, data=timestamp + "\n" + secret)))}，
 * 结果以 {@code &timestamp=<ts>&sign=<sign>} 追加到 webhookUrl。
 * 独立纯函数类便于固定向量单测钉死算法不漂移。
 */
public final class DingTalkSigner {

    private DingTalkSigner() {
    }

    /** 计算加签名（timestamp 毫秒字符串 + secret → URL 编码后的 sign）。 */
    public static String sign(String timestamp, String secret) {
        try {
            String data = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return URLEncoder.encode(Base64.getEncoder().encodeToString(digest), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("钉钉加签失败", e);
        }
    }

    /** webhookUrl 追加 timestamp 与 sign（已有 query 用 &，否则 ?）。 */
    public static String appendSign(String webhookUrl, String timestamp, String secret) {
        String sep = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + sign(timestamp, secret);
    }
}

package com.openforge.connector.spi;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 飞书自定义机器人签名（飞书官方算法，长任务书扩展包③，与钉钉不同）：
 * {@code sign = Base64(HmacSHA256(key = timestamp + "\n" + secret, data = ""))}——
 * timestamp+"\n"+secret 是 HMAC **key**，签的是空字符串；结果放 payload 的 {@code sign}/{@code timestamp} 字段。
 */
public final class FeishuSigner {

    private FeishuSigner() {
    }

    /** 计算 payload 签名（timestamp 秒字符串 + secret → Base64）。 */
    public static String sign(String timestamp, String secret) {
        try {
            String key = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        } catch (Exception e) {
            throw new IllegalStateException("飞书签名失败", e);
        }
    }
}

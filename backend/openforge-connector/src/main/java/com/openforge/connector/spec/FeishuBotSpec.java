package com.openforge.connector.spec;

import java.util.Map;

/**
 * 飞书自定义机器人 spec（schemaVersion=1，连接器扩展包 v1.22 ③）。
 * 签名：凭据 WEBHOOK_SECRET（secret）→ FeishuSigner（payload 内带 timestamp/sign）。
 */
public record FeishuBotSpec(
        int schemaVersion,
        String webhookUrl,
        String msgType,
        String textTemplate,
        int timeoutMs,
        String credentialRef,
        Map<String, Object> parameterSchema) {
}

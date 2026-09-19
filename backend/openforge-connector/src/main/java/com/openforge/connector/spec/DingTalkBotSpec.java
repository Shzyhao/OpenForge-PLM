package com.openforge.connector.spec;

import java.util.Map;

/**
 * 钉钉自定义机器人 spec（schemaVersion=1，连接器扩展包 v1.22 ②）。
 * 加签：凭据 WEBHOOK_SECRET（secret）→ DingTalkSigner。
 */
public record DingTalkBotSpec(
        int schemaVersion,
        String webhookUrl,
        String msgtype,
        String title,
        String textTemplate,
        String atMobiles,
        int timeoutMs,
        String credentialRef,
        Map<String, Object> parameterSchema) {
}

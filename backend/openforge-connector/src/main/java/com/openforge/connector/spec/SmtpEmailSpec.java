package com.openforge.connector.spec;

import java.util.Map;

/**
 * SMTP 邮件连接器 spec（schemaVersion=1，连接器扩展包 v1.22 ①）。
 * 解析与校验规则见 {@link ConnectorSpecs#parseSmtpEmail}。
 */
public record SmtpEmailSpec(
        int schemaVersion,
        String host,
        int port,
        boolean starttls,
        String from,
        String to,
        String subject,
        String bodyText,
        int timeoutMs,
        String credentialRef,
        Map<String, Object> parameterSchema) {
}

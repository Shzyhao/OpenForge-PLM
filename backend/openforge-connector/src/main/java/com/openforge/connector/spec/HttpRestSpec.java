package com.openforge.connector.spec;

import java.util.Map;

/**
 * HTTP/REST 连接器 spec（schemaVersion=1，集成编排器 MVP 设计 §4.1）。
 * 解析与校验规则见 {@link ConnectorSpecs}。
 */
public record HttpRestSpec(
        int schemaVersion,
        String method,
        String url,
        Map<String, String> headers,
        int timeoutMs,
        String credentialRef,
        Map<String, Object> parameterSchema,
        Map<String, Object> requestTemplate,
        Retry retry) {

    public record Retry(int maxAttempts, int backoffMs) {
    }
}

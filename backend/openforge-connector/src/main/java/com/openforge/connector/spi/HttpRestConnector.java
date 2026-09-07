package com.openforge.connector.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spec.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP/REST 连接器（集成编排器 MVP 设计 §5）：java.net.http.HttpClient，零新依赖。
 * - 认证注入：BASIC → Authorization: Basic base64(user:pass)；BEARER → Bearer；
 *   API_KEY_HEADER → extraJson.headerName 自定义头；
 * - 重试仅对 IO 异常与 5xx 生效（4xx 不重试）；退避固定值封顶；
 * - 响应体 1MB 硬上限（超出截断并标注）；错误摘要脱敏（凭据值出现即替换 ***）。
 */
@Slf4j
@Component
public class HttpRestConnector implements ConnectorSpi {

    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int ERROR_SNIPPET_LIMIT = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpRestConnector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER) // 重定向可能跳出白名单域，一律不跟
                .build();
    }

    @Override
    public String type() {
        return "HTTP_REST";
    }

    @Override
    public ConnectorResult execute(ConnectorExecution execution) {
        HttpRestSpec spec = execution.httpSpec();
        Map<String, Object> params = execution.params();
        ResolvedCredential credential = execution.credential();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(TemplateRenderer.renderUrl(spec.url(), params)))
                .timeout(Duration.ofMillis(spec.timeoutMs()));
        spec.headers().forEach((k, v) ->
                builder.header(k, TemplateRenderer.renderHeader(v, params)));
        applyCredential(builder, credential);

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (!"GET".equals(spec.method()) && spec.requestTemplate() != null && !spec.requestTemplate().isEmpty()) {
            try {
                Object rendered = TemplateRenderer.renderTemplate(spec.requestTemplate(), params);
                body = HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(rendered), StandardCharsets.UTF_8);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                return ConnectorResult.fail(null, "请求体序列化失败");
            }
            boolean hasContentType = spec.headers().keySet().stream()
                    .anyMatch(k -> k.equalsIgnoreCase("content-type"));
            if (!hasContentType) {
                builder.header("Content-Type", "application/json");
            }
        }
        builder.method(spec.method(), body);

        String secret = credential == null ? null : credential.secret();
        int attempts = spec.retry().maxAttempts();
        long backoff = spec.retry().backoffMs();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<byte[]> response = httpClient.send(
                        builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    byte[] raw = response.body();
                    boolean truncated = raw.length > MAX_BODY_BYTES;
                    int end = truncated ? MAX_BODY_BYTES : raw.length;
                    return ConnectorResult.ok(status,
                            new String(raw, 0, end, StandardCharsets.UTF_8), truncated);
                }
                if (status < 500 || attempt == attempts) {
                    return ConnectorResult.fail(status,
                            "上游返回 " + status + ": " + snippet(response.body(), secret));
                }
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt == attempts) {
                    return ConnectorResult.fail(null, "上游超时（timeoutMs=" + spec.timeoutMs() + "）");
                }
            } catch (Exception e) {
                if (attempt == attempts) {
                    return ConnectorResult.fail(null, "上游调用失败: " + sanitize(e.getMessage(), secret));
                }
            }
            sleep(backoff);
        }
        return ConnectorResult.fail(null, "上游调用失败"); // 不可达（循环内必返回）
    }

    private void applyCredential(HttpRequest.Builder builder, ResolvedCredential credential) {
        if (credential == null || "NONE".equals(credential.authType())) {
            return;
        }
        switch (credential.authType()) {
            case "BASIC" -> builder.header("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            credential.secret().getBytes(StandardCharsets.UTF_8)));
            case "BEARER" -> builder.header("Authorization", "Bearer " + credential.secret());
            case "API_KEY_HEADER" -> builder.header(credential.headerName() == null
                    ? "X-Api-Key" : credential.headerName(), credential.secret());
            default -> throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "不支持的认证类型: " + credential.authType());
        }
    }

    private void sleep(long backoffMs) {
        try {
            Thread.sleep(Math.min(backoffMs, 10_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "执行被中断");
        }
    }

    /** 错误响应体截断 + 凭据脱敏（secret 出现即替换 ***，防凭据经错误消息落日志）。 */
    private String snippet(byte[] raw, String secret) {
        String text = raw == null ? "" : new String(raw, 0, Math.min(raw.length, ERROR_SNIPPET_LIMIT),
                StandardCharsets.UTF_8);
        return sanitize(text, secret);
    }

    private String sanitize(String text, String secret) {
        if (text == null) {
            return "unknown";
        }
        if (secret != null && !secret.isEmpty() && text.contains(secret)) {
            return text.replace(secret, "***");
        }
        return text;
    }
}

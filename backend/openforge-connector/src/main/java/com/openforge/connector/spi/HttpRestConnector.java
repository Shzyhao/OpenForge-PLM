package com.openforge.connector.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spec.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * HTTP/REST 连接器（集成编排器 MVP 设计 §5）：出站统一走共享 {@link CloseableHttpClient}
 * （OutboundHttpConfig）——连接级 DNS 解析经 EgressPinningDnsResolver 固定校验（R6 SSRF 根治，
 * v1.19.0 起替换 JDK HttpClient；其无解析器注入点，校验与连接两次解析间存在重绑定窗口）。
 * - 认证注入：BASIC → Authorization: Basic base64(user:pass)；BEARER → Bearer；
 *   API_KEY_HEADER → extraJson.headerName 自定义头；
 * - 重试仅对 IO 异常与 5xx 生效（4xx 不重试）；退避固定值封顶；
 * - 响应体 1MB 硬上限（流式截断读取，超出部分不落内存并标注）；错误摘要脱敏（凭据值出现即替换 ***）。
 */
@Slf4j
@Component
public class HttpRestConnector implements ConnectorSpi {

    static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int ERROR_SNIPPET_LIMIT = 500;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpRestConnector(CloseableHttpClient outboundHttpClient, ObjectMapper objectMapper) {
        this.httpClient = outboundHttpClient;
        this.objectMapper = objectMapper;
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

        HttpUriRequestBase builder = new HttpUriRequestBase(spec.method(),
                URI.create(TemplateRenderer.renderUrl(spec.url(), params)));
        builder.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(10))
                .setResponseTimeout(Timeout.ofMilliseconds(spec.timeoutMs()))
                .build());
        spec.headers().forEach((k, v) ->
                builder.setHeader(k, TemplateRenderer.renderHeader(v, params)));
        applyCredential(builder, credential);

        if (!"GET".equals(spec.method()) && spec.requestTemplate() != null && !spec.requestTemplate().isEmpty()) {
            String json;
            try {
                Object rendered = TemplateRenderer.renderTemplate(spec.requestTemplate(), params);
                json = objectMapper.writeValueAsString(rendered);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                return ConnectorResult.fail(null, "请求体序列化失败");
            }
            // 实体不携带内容类型（null）：内容类型语义与原实现一致——仅当 spec 未声明时补默认头
            builder.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(json, (ContentType) null));
            if (!hasContentType(spec)) {
                builder.setHeader("Content-Type", "application/json");
            }
        }

        String secret = credential == null ? null : credential.secret();
        int attempts = spec.retry().maxAttempts();
        long backoff = spec.retry().backoffMs();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (ClassicHttpResponse response = httpClient.execute(builder)) {
                int status = response.getCode();
                if (status >= 200 && status < 300) {
                    ReadBody body = readBody(response);
                    return ConnectorResult.ok(status,
                            new String(body.bytes(), 0, body.length(), StandardCharsets.UTF_8),
                            body.truncated());
                }
                if (status < 500 || attempt == attempts) {
                    ReadBody body = readBody(response);
                    return ConnectorResult.fail(status,
                            "上游返回 " + status + ": " + snippet(body, secret));
                }
            } catch (Exception e) {
                if (attempt == attempts) {
                    // httpclient5 响应超时抛 TimeoutValueException（concurrent.TimeoutException 子类，未受检）
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        return ConnectorResult.fail(null, "上游超时（timeoutMs=" + spec.timeoutMs() + "）");
                    }
                    return ConnectorResult.fail(null, "上游调用失败: " + sanitize(e.getMessage(), secret));
                }
            }
            sleep(backoff);
        }
        return ConnectorResult.fail(null, "上游调用失败"); // 不可达（循环内必返回）
    }

    private boolean hasContentType(HttpRestSpec spec) {
        return spec.headers().keySet().stream().anyMatch(k -> k.equalsIgnoreCase("content-type"));
    }

    /** 响应体流式读取：最多 MAX_BODY_BYTES + 1 字节——超限即停（不再向内存读入），truncated 标注。 */
    private ReadBody readBody(ClassicHttpResponse response) throws java.io.IOException {
        if (response.getEntity() == null) {
            return new ReadBody(new byte[0], 0, false);
        }
        InputStream in = response.getEntity().getContent();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = in.read(buffer)) != -1) {
            int allowed = Math.min(read, MAX_BODY_BYTES + 1 - total);
            out.write(buffer, 0, allowed);
            total += allowed;
            if (total > MAX_BODY_BYTES) {
                truncated = true;
                total = MAX_BODY_BYTES;
                break;
            }
        }
        return new ReadBody(out.toByteArray(), total, truncated);
    }

    private record ReadBody(byte[] bytes, int length, boolean truncated) {
    }

    private void applyCredential(HttpUriRequestBase builder, ResolvedCredential credential) {
        if (credential == null || "NONE".equals(credential.authType())) {
            return;
        }
        switch (credential.authType()) {
            case "BASIC" -> builder.setHeader("Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            credential.secret().getBytes(StandardCharsets.UTF_8)));
            case "BEARER" -> builder.setHeader("Authorization", "Bearer " + credential.secret());
            case "API_KEY_HEADER" -> builder.setHeader(credential.headerName() == null
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
    private String snippet(ReadBody body, String secret) {
        String text = new String(body.bytes(), 0, Math.min(body.length(), ERROR_SNIPPET_LIMIT),
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

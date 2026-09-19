package com.openforge.connector.spi;

import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spec.FeishuBotSpec;
import com.openforge.connector.spec.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 飞书自定义机器人连接器（v1.22 扩展包③）：文本出站 + 官方签名。
 * <p>签名：凭据 {@code WEBHOOK_SECRET} → FeishuSigner（payload 内带 timestamp/sign 字段，非 query）；
 * 出站走共享 {@link CloseableHttpClient}（R6 固定解析语义）+ egressGuard.check(webhookUrl)。
 */
@Slf4j
@Component
public class FeishuBotConnector implements ConnectorSpi {

    private final CloseableHttpClient httpClient;
    private final EgressGuard egressGuard;

    public FeishuBotConnector(CloseableHttpClient httpClient, EgressGuard egressGuard) {
        this.httpClient = httpClient;
        this.egressGuard = egressGuard;
    }

    @Override
    public String type() {
        return ConnectorSpecs.TYPE_FEISHU_BOT;
    }

    @Override
    public ConnectorResult execute(ConnectorExecution execution) {
        FeishuBotSpec spec = execution.feishuSpec();
        if (spec == null) {
            return ConnectorResult.fail(null, "spec 缺失");
        }
        Map<String, Object> params = execution.params() == null ? Map.of() : execution.params();
        try {
            egressGuard.check(spec.webhookUrl());
            String content = String.valueOf(TemplateRenderer.renderTemplate(spec.textTemplate(), params));
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            StringBuilder body = new StringBuilder("{\"timestamp\":\"").append(timestamp)
                    .append("\",\"msg_type\":\"").append(spec.msgType()).append("\",\"content\":{\"text\":\"")
                    .append(escapeJson(content)).append("\"}");
            if (execution.credential() != null && execution.credential().secret() != null
                    && !execution.credential().secret().isBlank()) {
                body.append(",\"sign\":\"")
                        .append(FeishuSigner.sign(timestamp, execution.credential().secret()))
                        .append("\"");
            }
            body.append("}");

            HttpPost post = new HttpPost(spec.webhookUrl());
            post.setEntity(new StringEntity(body.toString(),
                    org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            String respBody;
            try (var response = httpClient.execute(post)) {
                respBody = new String(response.getEntity().getContent().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            // 飞书语义：HTTP 200 但 body.code!=0（或含 "msg":"success" 之外的错误）视为失败
            if (respBody.contains("\"code\":0") || respBody.contains("\"msg\":\"success\"")) {
                return ConnectorResult.ok(200, "feishu ok", false);
            }
            return ConnectorResult.fail(200, "飞书返回失败: " + truncate(respBody));
        } catch (com.openforge.common.api.BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("飞书机器人发送失败: {}", e.getMessage());
            return ConnectorResult.fail(null, "飞书发送失败: " + e.getMessage());
        }
    }

    private String escapeJson(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    private String truncate(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200);
    }
}

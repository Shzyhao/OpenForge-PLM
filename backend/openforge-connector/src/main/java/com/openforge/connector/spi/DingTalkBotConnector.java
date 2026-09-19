package com.openforge.connector.spi;

import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spec.DingTalkBotSpec;
import com.openforge.connector.spec.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 钉钉自定义机器人连接器（v1.22 扩展包②）：文本/markdown 出站 + 官方加签。
 * <p>加签：凭据 {@code WEBHOOK_SECRET} → DingTalkSigner（query 追加 timestamp/sign）；
 * 出站走共享 {@link CloseableHttpClient}（R6 固定解析语义）+ egressGuard.check(webhookUrl) 既有语义。
 */
@Slf4j
@Component
public class DingTalkBotConnector implements ConnectorSpi {

    private final CloseableHttpClient httpClient;
    private final EgressGuard egressGuard;

    public DingTalkBotConnector(CloseableHttpClient httpClient, EgressGuard egressGuard) {
        this.httpClient = httpClient;
        this.egressGuard = egressGuard;
    }

    @Override
    public String type() {
        return ConnectorSpecs.TYPE_DINGTALK_BOT;
    }

    @Override
    public ConnectorResult execute(ConnectorExecution execution) {
        DingTalkBotSpec spec = execution.dingtalkSpec();
        if (spec == null) {
            return ConnectorResult.fail(null, "spec 缺失");
        }
        Map<String, Object> params = execution.params() == null ? Map.of() : execution.params();
        try {
            egressGuard.check(spec.webhookUrl());
            String url = execution.credential() == null || execution.credential().secret() == null
                    || execution.credential().secret().isBlank()
                    ? spec.webhookUrl()
                    : DingTalkSigner.appendSign(spec.webhookUrl(),
                            String.valueOf(System.currentTimeMillis()), execution.credential().secret());

            String content = String.valueOf(TemplateRenderer.renderTemplate(spec.textTemplate(), params));
            StringBuilder body = new StringBuilder("{");
            if ("markdown".equals(spec.msgtype())) {
                String title = spec.title() == null ? "" :
                        String.valueOf(TemplateRenderer.renderTemplate(spec.title(), params));
                body.append("\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"")
                        .append(escapeJson(title)).append("\",\"text\":\"")
                        .append(escapeJson(content)).append("\"}");
            } else {
                body.append("\"msgtype\":\"text\",\"text\":{\"content\":\"")
                        .append(escapeJson(content)).append("\"}");
            }
            if (spec.atMobiles() != null && !spec.atMobiles().isBlank()) {
                List<String> mobiles = new ArrayList<>();
                for (String m : spec.atMobiles().split(",")) {
                    if (!m.isBlank()) {
                        mobiles.add("\"" + m.trim() + "\"");
                    }
                }
                body.append(",\"at\":{\"atMobiles\":[").append(String.join(",", mobiles)).append("]}");
            }
            body.append("}");

            HttpPost post = new HttpPost(url);
            post.setEntity(new StringEntity(body.toString(),
                    org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            String respBody;
            try (var response = httpClient.execute(post)) {
                respBody = new String(response.getEntity().getContent().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            // 钉钉语义：HTTP 200 但 body.errcode!=0 视为失败
            if (respBody.contains("\"errcode\":0")) {
                return ConnectorResult.ok(200, "dingtalk ok", false);
            }
            return ConnectorResult.fail(200, "钉钉返回失败: " + truncate(respBody));
        } catch (com.openforge.common.api.BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("钉钉机器人发送失败: {}", e.getMessage());
            return ConnectorResult.fail(null, "钉钉发送失败: " + e.getMessage());
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

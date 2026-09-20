package com.openforge.workflow.client;

import com.openforge.common.tenant.TenantContext;
import com.openforge.common.tenant.TenantHeaderFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 站内通知摄取回退通道（v1.23 设计 §1.3）：事件总线关闭（dev 默认）时把任务事件
 * 同步投递给 auth 内部接口落通知库。尽力而为：失败仅告警不阻断审批主流程
 * （总线开启时由 auth 侧 NotifyEventConsumer 承接，本通道不启用——与 ChangeNotifyClient 同规）。
 */
@Slf4j
@Component
public class NotifyClient {

    private final RestClient restClient;
    private final String internalToken;

    public NotifyClient(@Value("${openforge.security.auth-base-url:http://localhost:8081}") String authBaseUrl,
                        @Value("${openforge.security.internal-token:openforge-internal-dev-token}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(authBaseUrl).build();
        this.internalToken = internalToken;
    }

    public void deliver(String eventType, Map<String, Object> payload) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/notifications")
                    .header("X-Internal-Token", internalToken)
                    .header(TenantHeaderFilter.HEADER_USER_TENANT,
                            String.valueOf(TenantContext.getTenantId()))
                    .body(Map.of("eventType", eventType, "payload", payload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("站内通知回退投递失败（不阻断）: type={} — {}", eventType, e.getMessage());
        }
    }
}

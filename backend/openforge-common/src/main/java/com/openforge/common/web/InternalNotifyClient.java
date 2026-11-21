package com.openforge.common.web;

import com.openforge.common.tenant.TenantContext;
import com.openforge.common.tenant.TenantHeaderFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 站内通知回退通道基座（v1.28 P5#26）：四域（doc/material/drawing/workflow）NotifyClient 同构收敛。
 * 事件总线关闭（dev 默认）时把域内事件 POST 到 auth /internal/notifications——best-effort，失败仅告警。
 * 子类以域前缀 bean 名装配（mono 防冲突，先例 WorkflowNotifyClient）。
 */
@Slf4j
public class InternalNotifyClient {

    protected final RestClient restClient;
    protected final String internalToken;

    public InternalNotifyClient(String authBaseUrl, String internalToken) {
        this.restClient = InternalRestClientFactory.builder().baseUrl(authBaseUrl).build();
        this.internalToken = internalToken;
    }

    /** 投递一条事件到 auth 站内通知（回退通道语义；租户随当前上下文透传）。 */
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

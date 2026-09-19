package com.openforge.drawing.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 图纸发布联动通知（v1.22 ECO 联动）：事件总线关闭（dev 默认）时的同步 HTTP 回退通道，
 * 通知 change 服务自动创建联动变更单。尽力而为：失败仅告警不阻断发布主流程
 * （MQ 启用时由 DrawingReleasedEventConsumer 承接，本通道不启用）。
 */
@Slf4j
@Component
public class ChangeNotifyClient {

    private final RestClient restClient;
    private final String internalToken;

    public ChangeNotifyClient(@Value("${openforge.change.base-url:http://localhost:8085}") String changeBaseUrl,
                              @Value("${openforge.security.internal-token:openforge-internal-dev-token}") String internalToken) {
        this.restClient = RestClient.builder().baseUrl(changeBaseUrl).build();
        this.internalToken = internalToken;
    }

    public void notifyDrawingReleased(Map<String, Object> payload) {
        try {
            restClient.post()
                    .uri("/api/v1/internal/change/drawing-released")
                    .header("X-Internal-Token", internalToken)
                    .header(com.openforge.common.tenant.TenantHeaderFilter.HEADER_USER_TENANT,
                            String.valueOf(com.openforge.common.tenant.TenantContext.getTenantId()))
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("图纸发布联动已通知 change: {}", payload.get("drawingNumber"));
        } catch (Exception e) {
            log.warn("图纸发布联动通知失败（不阻断）: {} — {}", payload.get("drawingNumber"), e.getMessage());
        }
    }
}

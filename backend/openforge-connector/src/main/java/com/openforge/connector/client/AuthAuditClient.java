package com.openforge.connector.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 跨服务操作审计客户端（连接器 MVP R8）：manage 操作经 auth 内部端点落 sys_audit_log，
 * 与 metadata→auth 权限注册同一内部令牌直连模式。
 * 尽力而为语义：事务提交后异步于业务发送，失败仅告警不阻断（对齐 auth 域内审计吞异常惯例）。
 */
@Slf4j
@Component
public class AuthAuditClient {

    private final RestClient authClient;
    private final String internalToken;

    public AuthAuditClient(
            @Value("${openforge.security.auth-base-url}") String authBaseUrl,
            @Value("${openforge.security.internal-token}") String internalToken) {
        this.internalToken = internalToken;
        this.authClient = RestClient.builder().baseUrl(authBaseUrl).build();
    }

    /** 记录审计；事务活跃时挂 afterCommit（避免回滚操作留下审计残影）。 */
    public void record(Long operatorId, String action, String targetType, String targetId, String detail) {
        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        if (operatorId != null) {
            body.put("operatorId", operatorId);
        }
        body.put("action", action);
        body.put("targetType", targetType);
        body.put("targetId", targetId == null ? "" : targetId);
        body.put("detail", detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(body);
                }
            });
        } else {
            send(body);
        }
    }

    private void send(Map<String, Object> body) {
        try {
            authClient.post()
                    .uri("/api/v1/internal/audit")
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("操作审计上报失败（不阻断业务）: action={}, target={} — {}",
                    body.get("action"), body.get("targetId"), e.getMessage());
        }
    }
}

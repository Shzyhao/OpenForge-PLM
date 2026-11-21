package com.openforge.common.web;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨服务操作审计客户端基座（v1.28 P5#26）：connector/drawing 两域 AuthAuditClient 同构收敛。
 * manage 操作经 auth 内部端点落 sys_audit_log；尽力而为：事务活跃挂 afterCommit（回滚不留审计残影），
 * 失败仅告警 + openforge_audit_report_fail_total{source} 计数（v1.27 P2#11）不阻断业务。
 * 子类提供 {@link #auditSource()} 指标标签并以域前缀 bean 名装配（mono 防冲突）。
 */
@Slf4j
public abstract class InternalAuditClient {

    private final RestClient authClient;
    private final String internalToken;

    protected InternalAuditClient(String authBaseUrl, String internalToken) {
        this.internalToken = internalToken;
        this.authClient = InternalRestClientFactory.builder().baseUrl(authBaseUrl).build();
    }

    /** 失败计数器的 source 标签（域子类提供，如 "connector-audit"）。 */
    protected abstract String auditSource();

    /** 记录审计；事务活跃时挂 afterCommit（避免回滚操作留下审计残影）。 */
    public void record(Long operatorId, String action, String targetType, String targetId, String detail) {
        HashMap<String, Object> body = new HashMap<>();
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
            log.warn("审计上报失败（不阻断业务）: source={}, action={} — {}",
                    auditSource(), body.get("action"), e.getMessage());
            Metrics.counter("openforge_audit_report_fail_total", "source", auditSource()).increment();
        }
    }
}

package com.openforge.common.event;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Metrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * outbox 补发器（B2 设计 P2）：扫描 sys_event_outbox 未发送行重发并标记——
 * 消除「业务已提交但事件发送失败」的丢失窗口。每服务内嵌一个 relay（enabled=true 时装配）。
 * 单轮 LIMIT 10 + 60s 间隔：broker 停机时失败快速累积，retry_count ≥ 32 的行不再扫描
 * （死信语义，靠 openforge_outbox_dead_total 告警发现）。
 * 属主过滤（v1.27 P1#3）：仅认领 producer=本服务 的行——多服务共扫共享表时不再互相代发
 * （at-least-once 语义下重复补发仍由消费侧 sys_event_consumed 去重承接，但代发会放大重试计数）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "openforge.event.enabled", havingValue = "true")
public class EventOutboxRelay {

    private static final int MAX_RETRY = 32;
    private static final int BATCH = 10;

    private final JdbcTemplate jdbc;
    private final EventPublisher publisher;

    public EventOutboxRelay(JdbcTemplate jdbc, EventPublisher publisher) {
        this.jdbc = jdbc;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${openforge.event.relay-interval-millis:60000}")
    public void relay() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, event_id, topic, tag, payload, retry_count FROM sys_event_outbox "
                        + "WHERE sent_at IS NULL AND retry_count < ? AND producer = ? ORDER BY id LIMIT ?",
                MAX_RETRY, publisher.producerName(), BATCH);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String topic = String.valueOf(row.get("topic"));
            String tag = String.valueOf(row.get("tag"));
            String eventId = String.valueOf(row.get("event_id"));
            String payload = String.valueOf(row.get("payload"));
            int retryCount = ((Number) row.get("retry_count")).intValue();
            try {
                boolean ok = publisher.sendRaw(topic, tag, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), eventId);
                if (ok) {
                    jdbc.update("UPDATE sys_event_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", id);
                    Metrics.counter("openforge_events_relay_sent_total", "topic", topic).increment();
                } else {
                    jdbc.update("UPDATE sys_event_outbox SET retry_count = retry_count + 1 WHERE id = ?", id);
                    onRetryFailed(topic, retryCount);
                }
            } catch (Exception e) {
                jdbc.update("UPDATE sys_event_outbox SET retry_count = retry_count + 1 WHERE id = ?", id);
                onRetryFailed(topic, retryCount);
                log.warn("outbox 补发失败: id={}, topic={} — {}", id, topic, e.getMessage());
            }
        }
    }

    /** 失败计数；跨过死信阈值（retry+1 ≥ MAX_RETRY）的行额外计入 openforge_outbox_dead_total（P2#11 告警源）。 */
    private void onRetryFailed(String topic, int retryCount) {
        Metrics.counter("openforge_events_relay_failed_total", "topic", topic).increment();
        if (retryCount + 1 >= MAX_RETRY) {
            Metrics.counter("openforge_outbox_dead_total", "topic", topic).increment();
            log.error("outbox 行进入死信（不再补发）: topic={}, 建议人工核查 broker/消费组", topic);
        }
    }
}

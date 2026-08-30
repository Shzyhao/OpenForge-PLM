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
 * （死信语义，靠告警发现）。
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
                        + "WHERE sent_at IS NULL AND retry_count < ? ORDER BY id LIMIT ?",
                MAX_RETRY, BATCH);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String topic = String.valueOf(row.get("topic"));
            String tag = String.valueOf(row.get("tag"));
            String eventId = String.valueOf(row.get("event_id"));
            String payload = String.valueOf(row.get("payload"));
            try {
                boolean ok = publisher.sendRaw(topic, tag, payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), eventId);
                if (ok) {
                    jdbc.update("UPDATE sys_event_outbox SET sent_at = CURRENT_TIMESTAMP WHERE id = ?", id);
                    Metrics.counter("openforge_events_relay_sent_total", "topic", topic).increment();
                } else {
                    jdbc.update("UPDATE sys_event_outbox SET retry_count = retry_count + 1 WHERE id = ?", id);
                    Metrics.counter("openforge_events_relay_failed_total", "topic", topic).increment();
                }
            } catch (Exception e) {
                jdbc.update("UPDATE sys_event_outbox SET retry_count = retry_count + 1 WHERE id = ?", id);
                Metrics.counter("openforge_events_relay_failed_total", "topic", topic).increment();
                log.warn("outbox 补发失败: id={}, topic={} — {}", id, topic, e.getMessage());
            }
        }
    }
}

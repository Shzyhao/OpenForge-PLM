package com.openforge.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * 事件消费骨架（B2 设计 3.4）：幂等去重（sys_event_consumed）→ 租户上下文回填 →
 * MDC traceId 串联。子类实现 handle(env) 承载业务；异常抛出触发 broker 递增重试，
 * 超 maxReconsumeTimes 进死信 %DLQ%{consumerGroup}。
 */
@Slf4j
public abstract class AbstractEventConsumer {

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;
    private final String consumerName;

    protected AbstractEventConsumer(JdbcTemplate jdbc, ObjectMapper mapper, String consumerName) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.consumerName = consumerName;
    }

    /** 解析消息 → 幂等占位 → 上下文回填。返回 empty 表示重复投递或不可解析（直接 ACK 丢弃）。 */
    protected Optional<EventEnvelope> consume(MessageExt msg) {
        try {
            EventEnvelope env = mapper.readValue(msg.getBody(), EventEnvelope.class);
            try {
                jdbc.update("INSERT INTO sys_event_consumed (event_id, consumer, consumed_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                        env.getEventId(), consumerName);
            } catch (DuplicateKeyException e) {
                Metrics.counter("openforge_events_duplicate_total", "consumer", consumerName).increment();
                log.debug("事件重复投递已跳过: eventId={}, consumer={}", env.getEventId(), consumerName);
                return Optional.empty();
            }
            com.openforge.common.tenant.TenantContext.setTenantId(env.getTenantId());
            if (env.getTraceId() != null) {
                MDC.put(com.openforge.common.trace.TraceIdFilter.MDC_KEY, env.getTraceId());
            }
            return Optional.of(env);
        } catch (Exception e) {
            log.warn("事件解析失败（ACK 丢弃）: consumer={}, msg={} — {}", consumerName, msg.getMsgId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** 请求结束清理（租户上下文是 ThreadLocal，消费线程复用必须清理）。 */
    protected void finish() {
        com.openforge.common.tenant.TenantContext.clear();
        MDC.clear();
    }
}

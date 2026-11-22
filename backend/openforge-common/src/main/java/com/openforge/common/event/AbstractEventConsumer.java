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
 * 健康指示（v1.27 P2#9）：openforge_mq_consumer_up{group} gauge——启动成功置 1、
 * 失败置 0（消费者启动失败后进程看似健康的问题由 Prometheus 告警承接，monitoring/prometheus-rules.yml）。
 * at-least-once 语义（v1.31 P0-1 修复）：子类经 {@link #process} 执行业务，失败时补偿删除
 * 幂等行使重投可再处理——此前幂等行先于副作用以自动提交落库，失败重投被去重行拦截 ACK，
 * 事件永久丢失且到不了 DLQ（"首败即丢"）。残留窗口：补偿删除本身失败（DB 断）时该事件
 * 重投仍会被拦截（openforge_events_idem_rollback_failed_total 计数可观测）。
 */
@Slf4j
public abstract class AbstractEventConsumer {

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;
    private final String consumerName;
    private final java.util.concurrent.atomic.AtomicInteger consumerUp = new java.util.concurrent.atomic.AtomicInteger(0);

    protected AbstractEventConsumer(JdbcTemplate jdbc, ObjectMapper mapper, String consumerName) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.consumerName = consumerName;
        Metrics.gauge("openforge_mq_consumer_up", io.micrometer.core.instrument.Tags.of("group", consumerName), consumerUp);
    }

    /** 子类消费者启动成功后调用（gauge→1）。 */
    protected void markConsumerStarted() {
        consumerUp.set(1);
    }

    /** 子类消费者启动失败时调用（gauge 保持 0，供告警发现）。 */
    protected void markConsumerFailed() {
        consumerUp.set(0);
    }

    /** 解析消息 → 幂等占位 → 上下文回填。返回 empty 表示重复投递或不可解析（直接 ACK 丢弃）。
     * 幂等行写入失败（非重复键，如 DB 瞬断）时上抛交由 broker 重试——不得误 ACK（v1.31 P0-1）。 */
    protected Optional<EventEnvelope> consume(MessageExt msg) {
        EventEnvelope env;
        try {
            env = mapper.readValue(msg.getBody(), EventEnvelope.class);
        } catch (Exception e) {
            log.warn("事件解析失败（ACK 丢弃）: consumer={}, msg={} — {}", consumerName, msg.getMsgId(), e.getMessage());
            return Optional.empty();
        }
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
    }

    /** 消费模板（v1.31 P0-1）：去重占位 → 执行业务 → 失败时补偿删除幂等行再上抛（重投可再处理）。
     * 子类消息循环应以本方法替代 {@code consume(msg).ifPresent(this::handle)} 直连写法。 */
    protected void process(MessageExt msg, java.util.function.Consumer<EventEnvelope> handler) {
        Optional<EventEnvelope> parsed = consume(msg);
        if (parsed.isEmpty()) {
            return;
        }
        EventEnvelope env = parsed.get();
        try {
            handler.accept(env);
        } catch (Exception e) {
            try {
                jdbc.update("DELETE FROM sys_event_consumed WHERE event_id = ? AND consumer = ?",
                        env.getEventId(), consumerName);
            } catch (Exception rollbackFailure) {
                Metrics.counter("openforge_events_idem_rollback_failed_total", "consumer", consumerName).increment();
                log.error("幂等行补偿删除失败（该事件重投将被去重拦截）: eventId={}, consumer={} — {}",
                        env.getEventId(), consumerName, rollbackFailure.getMessage());
            }
            throw e;
        }
    }

    /** 请求结束清理（租户上下文是 ThreadLocal，消费线程复用必须清理）。 */
    protected void finish() {
        com.openforge.common.tenant.TenantContext.clear();
        MDC.clear();
    }
}

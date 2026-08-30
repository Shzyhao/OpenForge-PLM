package com.openforge.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件总线生产者出口（B2 设计 3.3 + P2 outbox 可靠性）：
 * - enabled=false（默认）：publish 返回 false，调用方回退既有同步 HTTP——零连接零线程；
 * - enabled=true 且事务活跃：**同事务写入 sys_event_outbox**（原子，随业务回滚）+ afterCommit
 *   异步发送；发送成功标记 sent，失败留存交由 EventOutboxRelay 补发——丢失窗口消除；
 * - enabled=true 无事务（自动提交场景）：直发，失败落 outbox 交 relay；
 * - 熔断：连续 3 次发送失败 60s 内快速返回（outbox 落库失败同计）；
 * - 信封自动填充 producer/tenantId/traceId；at-least-once 语义由消费侧 sys_event_consumed 去重承接。
 */
@Slf4j
@Component
public class EventPublisher implements DisposableBean {

    static final long TRIP_OPEN_MILLIS = 60_000L;
    private static final int TRIP_THRESHOLD = 3;

    private final boolean enabled;
    private final String producerName;
    private final String namesrvAddr;
    private final long sendTimeoutMillis;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Object producerLock = new Object();
    private volatile DefaultMQProducer producer;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil;

    public EventPublisher(
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            @Value("${openforge.event.enabled:false}") boolean enabled,
            @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr,
            @Value("${openforge.event.send-timeout-millis:3000}") long sendTimeoutMillis,
            @Value("${openforge.event.producer-name:${spring.application.name:unknown}}") String producerName) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.namesrvAddr = namesrvAddr;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.producerName = producerName;
        if (enabled) {
            log.info("事件总线已启用: namesrv={}, producer={}", namesrvAddr, producerName);
        }
    }

    /**
     * 发送业务事件。返回语义：
     * - true = 已进入 MQ 管道（直发成功，或 outbox 已排队由 relay 补发）——调用方**不再**回退 HTTP；
     * - false = 未启用（调用方回退同步 HTTP）或 outbox 落库失败（极端场景，事件丢失并已 ERROR）。
     */
    public boolean publish(String topic, String eventType, Map<String, Object> payload) {
        Metrics.counter("openforge_events_published_total", "topic", topic).increment();
        if (!enabled) {
            Metrics.counter("openforge_events_fallback_total", "topic", topic).increment();
            return false;
        }
        if (System.currentTimeMillis() < circuitOpenUntil) {
            Metrics.counter("openforge_events_fallback_total", "topic", topic).increment();
            return false;
        }
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID().toString(), eventType, 1,
                LocalDateTime.now().toString(), producerName,
                com.openforge.common.tenant.TenantContext.getTenantId(),
                MDC.get(com.openforge.common.trace.TraceIdFilter.MDC_KEY),
                payload);
        byte[] body;
        try {
            body = mapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("事件信封序列化失败（事件丢失）: topic={}, type={} — {}", topic, eventType, e.getMessage());
            return false;
        }
        String eventId = envelope.getEventId();

        // 事务活跃：outbox 同事务落库（原子），afterCommit 再发送
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                jdbc.update("INSERT INTO sys_event_outbox (event_id, topic, tag, payload, producer) VALUES (?, ?, ?, ?, ?)",
                        eventId, topic, eventType, new String(body, StandardCharsets.UTF_8), producerName);
            } catch (Exception e) {
                onFailure(topic);
                log.error("outbox 落库失败（事件丢失，业务已提交前）: topic={}, type={} — {}", topic, eventType, e.getMessage());
                return false;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trySend(topic, eventType, body, eventId);
                }
            });
            return true;
        }

        // 无事务（自动提交场景）：直发，失败落 outbox 交 relay
        if (!trySend(topic, eventType, body, eventId)) {
            try {
                jdbc.update("INSERT INTO sys_event_outbox (event_id, topic, tag, payload, producer) VALUES (?, ?, ?, ?, ?)",
                        eventId, topic, eventType, new String(body, StandardCharsets.UTF_8), producerName);
            } catch (Exception e) {
                log.error("直发失败且 outbox 落库失败（事件丢失）: topic={}, type={} — {}", topic, eventType, e.getMessage());
                return false;
            }
        }
        return true;
    }

    /** 直发（供 relay 复用）。成功 true；失败/熔断 false。 */
    public boolean sendRaw(String topic, String eventType, byte[] body, String eventId) {
        return trySend(topic, eventType, body, eventId);
    }

    private boolean trySend(String topic, String eventType, byte[] body, String eventId) {
        if (System.currentTimeMillis() < circuitOpenUntil) {
            Metrics.counter("openforge_events_fallback_total", "topic", topic).increment();
            return false;
        }
        try {
            DefaultMQProducer p = producer();
            Message msg = new Message(topic, eventType, body);
            SendResult result = p.send(msg, sendTimeoutMillis);
            boolean ok = result != null && result.getSendStatus() == SendStatus.SEND_OK;
            if (ok) {
                consecutiveFailures.set(0);
                Metrics.counter("openforge_events_sent_total", "topic", topic).increment();
            } else {
                onFailure(topic);
            }
            return ok;
        } catch (Exception e) {
            onFailure(topic);
            log.warn("事件发送失败: topic={}, type={}, eventId={} — {}", topic, eventType, eventId, e.getMessage());
            return false;
        }
    }

    private void onFailure(String topic) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + TRIP_OPEN_MILLIS;
            consecutiveFailures.set(0);
            log.warn("事件发送连续失败 {} 次，熔断 {}s（outbox 行由 relay 补发）", failures, TRIP_OPEN_MILLIS / 1000);
        }
        Metrics.counter("openforge_events_send_failed_total", "topic", topic).increment();
    }

    private DefaultMQProducer producer() {
        DefaultMQProducer p = producer;
        if (p == null) {
            synchronized (producerLock) {
                if (producer == null) {
                    DefaultMQProducer created = new DefaultMQProducer("openforge-" + producerName);
                    created.setNamesrvAddr(namesrvAddr);
                    created.setSendMsgTimeout((int) Math.min(sendTimeoutMillis, 10_000));
                    try {
                        created.start();
                    } catch (Exception e) {
                        throw new IllegalStateException("MQ producer 启动失败: " + e.getMessage(), e);
                    }
                    producer = created;
                    log.info("MQ producer 已启动: group=openforge-{}", producerName);
                }
                p = producer;
            }
        }
        return p;
    }

    @Override
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}

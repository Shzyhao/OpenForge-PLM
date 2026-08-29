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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件总线生产者出口（B2 设计 3.3）：
 * - enabled=false（默认）：publish 返回 false，调用方回退既有同步 HTTP——本地/CI 零依赖、
 *   行为与 v1.3.0 一致；客户端 lazy 创建，关闭态零连接零线程；
 * - enabled=true：事务提交后同步发送（调用方经 afterCommit），失败返回 false 并计数告警
 *   （P1 丢失窗口=现状尽力而为语义，P2 outbox 补齐）。
 * 性能护栏（画像 §5 自检项）：连续失败熔断 60s，broker 停机时不拖垮发布路径。
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
    private final Object producerLock = new Object();
    private volatile DefaultMQProducer producer;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil;

    public EventPublisher(
            ObjectMapper mapper,
            @Value("${openforge.event.enabled:false}") boolean enabled,
            @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr,
            @Value("${openforge.event.send-timeout-millis:3000}") long sendTimeoutMillis,
            @Value("${openforge.event.producer-name:${spring.application.name:unknown}}") String producerName) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.namesrvAddr = namesrvAddr;
        this.sendTimeoutMillis = sendTimeoutMillis;
        this.producerName = producerName;
        if (enabled) {
            log.info("事件总线已启用: namesrv={}, producer={}", namesrvAddr, producerName);
        }
    }

    /**
     * 发送业务事件。返回 true=已入 MQ；false=未发送（未启用/熔断/失败）——
     * 调用方据此回退既有同步路径（如发布流水线的 knowledge HTTP 客户端）。
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
        try {
            DefaultMQProducer p = producer();
            EventEnvelope envelope = new EventEnvelope(
                    UUID.randomUUID().toString(), eventType, 1,
                    LocalDateTime.now().toString(), producerName,
                    com.openforge.common.tenant.TenantContext.getTenantId(),
                    MDC.get(com.openforge.common.trace.TraceIdFilter.MDC_KEY),
                    payload);
            Message msg = new Message(topic, eventType,
                    mapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
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
            log.warn("事件发送失败（调用方回退）: topic={}, type={} — {}", topic, eventType, e.getMessage());
            return false;
        }
    }

    private void onFailure(String topic) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= TRIP_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + TRIP_OPEN_MILLIS;
            consecutiveFailures.set(0);
            log.warn("事件发送连续失败 {} 次，熔断 {}s（期间走调用方回退）", failures, TRIP_OPEN_MILLIS / 1000);
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

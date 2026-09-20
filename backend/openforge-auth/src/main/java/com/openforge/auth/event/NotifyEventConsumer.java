package com.openforge.auth.event;

import com.openforge.auth.service.NotifyService;
import com.openforge.common.event.AbstractEventConsumer;
import com.openforge.common.event.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 站内通知 MQ 消费者（v1.23 设计 §1.3）——B2 设计预留的 notify 消费组落地。
 * 与 connector 消费者同骨架（幂等 + 租户/MDC 回填 + FIRST_OFFSET 时间闸门防历史回放），
 * 仅订阅 openforge-task（task.created/completed）。启动失败仅告警：HTTP 回退通道与
 * 收件箱 API 不依赖本消费者（总线关闭的默认 dev 态通知功能完整可用）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "openforge.event.enabled", havingValue = "true")
public class NotifyEventConsumer extends AbstractEventConsumer
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    static final String TOPIC = "openforge-task";
    private final NotifyService notifyService;
    private final String namesrvAddr;
    private final long staleSkewMinutes;
    /** 启动时间闸门：occurredAt 早于该值的信封 ACK 跳过（防首次上线回放历史通知） */
    private volatile java.time.LocalDateTime startedAt;
    private DefaultMQPushConsumer consumer;

    public NotifyEventConsumer(JdbcTemplate jdbc,
                               com.fasterxml.jackson.databind.ObjectMapper mapper,
                               NotifyService notifyService,
                               @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr,
                               @Value("${openforge.notify.stale-skew-minutes:2}") long staleSkewMinutes) {
        super(jdbc, mapper, "openforge-notify");
        this.notifyService = notifyService;
        this.namesrvAddr = namesrvAddr;
        this.staleSkewMinutes = staleSkewMinutes;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        startedAt = java.time.LocalDateTime.now().minusMinutes(Math.max(0, staleSkewMinutes));
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("openforge-notify");
            c.setNamesrvAddr(namesrvAddr);
            c.setConsumeThreadMin(1);
            c.setConsumeThreadMax(2);
            c.setMaxReconsumeTimes(1);
            c.setConsumeFromWhere(
                    org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            c.subscribe(TOPIC, "*");
            c.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    this::onMessages);
            c.start();
            this.consumer = c;
            log.info("站内通知消费者已启动: namesrv={}, topic={}, 时间闸门={}", namesrvAddr, TOPIC, startedAt);
        } catch (Exception e) {
            log.error("站内通知消费者启动失败（HTTP 回退通道与收件箱不受影响）: {}", e.getMessage());
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        for (MessageExt msg : msgs) {
            try {
                consume(msg).ifPresent(this::handle);
            } catch (Exception e) {
                log.warn("通知消费基础设施异常将重试: msgId={} — {}", msg.getMsgId(), e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } finally {
                finish();
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /** 事件→通知落库（public 供测试直调，不依赖 MQ）。早于时间闸门的历史事件 ACK 跳过。 */
    public void handle(EventEnvelope env) {
        if (startedAt != null && env.getOccurredAt() != null) {
            try {
                if (java.time.LocalDateTime.parse(env.getOccurredAt()).isBefore(startedAt)) {
                    log.debug("历史事件已按时间闸门跳过: type={}, occurredAt={}", env.getEventType(), env.getOccurredAt());
                    return;
                }
            } catch (Exception ignored) {
                // 解析失败按非历史处理（保守，宁投递勿丢）
            }
        }
        notifyService.ingest(env.getEventType(), env.getPayload());
        io.micrometer.core.instrument.Metrics.counter("openforge_events_consumed_total",
                "consumer", "openforge-notify", "type", env.getEventType()).increment();
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

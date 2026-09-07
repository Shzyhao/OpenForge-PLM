package com.openforge.connector.event;

import com.openforge.common.event.AbstractEventConsumer;
import com.openforge.common.event.EventEnvelope;
import com.openforge.connector.service.TriggerDispatcher;
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
import java.util.Map;

/**
 * 连接器事件消费者（P2-2 §12.2）：订阅平台既有主题，按已发布 EVENT 触发配置
 * （topic+tag 匹配）分发执行。与 knowledge 消费者同骨架（B2 设计 3.4）：
 * sys_event_consumed 幂等 + 租户/MDC 回填。
 * 起点语义：FIRST_OFFSET + 启动时间闸门——新消费组 LAST_OFFSET 时 rebalance 完成前的
 * 消息永久错过（CI 真实 MQ 回路实纱）；FIRST_OFFSET 全量拉取但只处理信封 occurredAt
 * 晚于（启动时刻 - 时钟偏差容忍）的事件：既不回放首次上线前的历史（触发是副作用执行），
 * 也不丢启动窗口内的事件。停机期间的事件按 at-most-once 语义刻意不补。
 * 执行失败不抛异常（RECONSUME 会被幂等行判重 ACK，重投无意义）——
 * 失败即落 sys_connector_dlq 死信，由人工重放（对齐 B2 死信语义，应用级承接）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "openforge.event.enabled", havingValue = "true")
public class ConnectorEventConsumer extends AbstractEventConsumer
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final TriggerDispatcher dispatcher;
    private final String namesrvAddr;
    private final List<String> subscribeTopics;
    private final long staleSkewMinutes;
    /** 启动时间闸门：occurredAt 早于此值的信封直接 ACK 跳过（防首次上线回放历史）。 */
    private volatile java.time.LocalDateTime startedAt;
    private DefaultMQPushConsumer consumer;

    public ConnectorEventConsumer(JdbcTemplate jdbc,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper,
                                  TriggerDispatcher dispatcher,
                                  @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr,
                                  @Value("${openforge.connector.trigger.subscribe-topics:"
                                          + com.openforge.connector.spec.TriggerSpecs.DEFAULT_TOPICS + "}")
                                  String subscribeTopics,
                                  @Value("${openforge.connector.trigger.stale-skew-minutes:2}")
                                  long staleSkewMinutes) {
        super(jdbc, mapper, "openforge-connector-trigger");
        this.dispatcher = dispatcher;
        this.namesrvAddr = namesrvAddr;
        this.staleSkewMinutes = staleSkewMinutes;
        this.subscribeTopics = java.util.Arrays.stream(subscribeTopics.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        startedAt = java.time.LocalDateTime.now().minusMinutes(Math.max(0, staleSkewMinutes));
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("openforge-connector-trigger");
            c.setNamesrvAddr(namesrvAddr);
            c.setConsumeThreadMin(1);
            c.setConsumeThreadMax(3);
            c.setMaxReconsumeTimes(1);
            // 新消费组从头拉取 + 时间闸门过滤：LAST_OFFSET 会永久错过 rebalance 前的消息
            c.setConsumeFromWhere(
                    org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            for (String topic : subscribeTopics) {
                c.subscribe(topic, "*");
            }
            c.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    this::onMessages);
            c.start();
            this.consumer = c;
            log.info("连接器事件消费者已启动: namesrv={}, topics={}, 时间闸门={}",
                    namesrvAddr, subscribeTopics, startedAt);
        } catch (Exception e) {
            log.error("连接器事件消费者启动失败（触发能力不可用，invoke/API 调用不受影响）: {}", e.getMessage());
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        for (MessageExt msg : msgs) {
            try {
                consume(msg).ifPresent(env -> handle(msg.getTopic(), env));
            } catch (Exception e) {
                log.warn("事件分发基础设施异常将重试: msgId={} — {}", msg.getMsgId(), e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } finally {
                finish();
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /** 事件→连接器分发（public 供 H2 测试直调，不依赖 MQ）。早于时间闸门的历史事件 ACK 跳过。 */
    public void handle(String topic, EventEnvelope env) {
        if (isStale(env)) {
            io.micrometer.core.instrument.Metrics.counter("openforge_connector_events_stale_total",
                    "consumer", "openforge-connector-trigger").increment();
            log.debug("历史事件已按时间闸门跳过: type={}, occurredAt={}", env.getEventType(), env.getOccurredAt());
            return;
        }
        int fired = dispatcher.dispatchEvent(topic, env.getEventType(), env.getPayload(), env.getEventId());
        if (fired > 0) {
            io.micrometer.core.instrument.Metrics.counter("openforge_events_consumed_total",
                    "consumer", "openforge-connector-trigger", "type", env.getEventType()).increment(fired);
            log.info("事件触发执行: topic={}, type={}, 命中连接器={}", topic, env.getEventType(), fired);
        }
    }

    /** 信封时间早于启动闸门 → 历史事件；解析失败按非历史处理（保守，宁执行勿丢）。 */
    private boolean isStale(EventEnvelope env) {
        if (startedAt == null || env.getOccurredAt() == null) {
            return false;
        }
        try {
            return java.time.LocalDateTime.parse(env.getOccurredAt()).isBefore(startedAt);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

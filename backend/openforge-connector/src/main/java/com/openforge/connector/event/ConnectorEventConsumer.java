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
 * 差异语义：CONSUME_FROM_LAST_OFFSET——触发执行是副作用而非沉淀，功能启用时
 * 不回放历史事件（防首次上线对旧事件批量外呼）。
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
    private DefaultMQPushConsumer consumer;

    public ConnectorEventConsumer(JdbcTemplate jdbc,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper,
                                  TriggerDispatcher dispatcher,
                                  @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr,
                                  @Value("${openforge.connector.trigger.subscribe-topics:"
                                          + com.openforge.connector.spec.TriggerSpecs.DEFAULT_TOPICS + "}")
                                  String subscribeTopics) {
        super(jdbc, mapper, "openforge-connector-trigger");
        this.dispatcher = dispatcher;
        this.namesrvAddr = namesrvAddr;
        this.subscribeTopics = java.util.Arrays.stream(subscribeTopics.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("openforge-connector-trigger");
            c.setNamesrvAddr(namesrvAddr);
            c.setConsumeThreadMin(1);
            c.setConsumeThreadMax(3);
            c.setMaxReconsumeTimes(1);
            // 触发是副作用执行：从订阅时刻起消费，不回放历史
            c.setConsumeFromWhere(
                    org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            for (String topic : subscribeTopics) {
                c.subscribe(topic, "*");
            }
            c.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    this::onMessages);
            c.start();
            this.consumer = c;
            log.info("连接器事件消费者已启动: namesrv={}, topics={}", namesrvAddr, subscribeTopics);
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

    /** 事件→连接器分发（public 供 H2 测试直调，不依赖 MQ）。 */
    public void handle(String topic, EventEnvelope env) {
        int fired = dispatcher.dispatchEvent(topic, env.getEventType(), env.getPayload(), env.getEventId());
        if (fired > 0) {
            io.micrometer.core.instrument.Metrics.counter("openforge_events_consumed_total",
                    "consumer", "openforge-connector-trigger", "type", env.getEventType()).increment(fired);
            log.info("事件触发执行: topic={}, type={}, 命中连接器={}", topic, env.getEventType(), fired);
        }
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

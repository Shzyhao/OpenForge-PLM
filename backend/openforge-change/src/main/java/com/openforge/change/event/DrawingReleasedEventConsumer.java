package com.openforge.change.event;

import com.openforge.change.service.EcrService;
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
import java.util.Map;

/**
 * change 事件消费者（v1.22 ECO 联动）：
 * - drawing.released：图纸发布 → 自动创建联动变更单（正常审批流，升版决策留给评审）；
 *   幂等键 drawingNumber@version 由 EcrService.autoCreateFromDrawing 保证。
 * 与同步 HTTP 回退（ChangeNotifyClient → ChangeInternalController）互补：
 * EVENT_ENABLED=true 时本消费者承接，false 时 drawing 侧直调内部端点。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "openforge.event.enabled", havingValue = "true")
public class DrawingReleasedEventConsumer extends AbstractEventConsumer
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final EcrService ecrService;
    private final String namesrvAddr;
    private DefaultMQPushConsumer consumer;

    public DrawingReleasedEventConsumer(JdbcTemplate jdbc,
                                        com.fasterxml.jackson.databind.ObjectMapper mapper,
                                        EcrService ecrService,
                                        @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr) {
        super(jdbc, mapper, "openforge-change");
        this.ecrService = ecrService;
        this.namesrvAddr = namesrvAddr;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("openforge-change");
            c.setNamesrvAddr(namesrvAddr);
            c.setConsumeThreadMin(2);
            c.setConsumeThreadMax(5);
            c.setMaxReconsumeTimes(3);
            c.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
            c.subscribe("openforge-drawing", "drawing.released");
            c.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    this::onMessages);
            c.start();
            this.consumer = c;
            log.info("change 事件消费者已启动: namesrv={}", namesrvAddr);
        } catch (Exception e) {
            log.error("change 事件消费者启动失败（ECO 联动 MQ 通道不可用，业务不受影响）: {}", e.getMessage());
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        for (MessageExt msg : msgs) {
            try {
                consume(msg).ifPresent(this::handle);
            } catch (Exception e) {
                log.warn("事件处理失败将重试（重试 {} 次）: msgId={} — {}",
                        msg.getReconsumeTimes(), msg.getMsgId(), e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } finally {
                finish();
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /** 业务分发（public 供 H2 单测直调；幂等由 autoCreateFromDrawing 标题键保证）。 */
    public void handle(EventEnvelope env) {
        Map<String, Object> payload = env.getPayload();
        if ("drawing.released".equals(env.getEventType())) {
            ecrService.autoCreateFromDrawing(payload);
        }
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

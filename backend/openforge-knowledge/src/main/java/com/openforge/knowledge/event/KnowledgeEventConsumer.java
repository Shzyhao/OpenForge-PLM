package com.openforge.knowledge.event;

import com.openforge.common.event.AbstractEventConsumer;
import com.openforge.common.event.EventEnvelope;
import com.openforge.knowledge.service.KnowledgeService;
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
 * knowledge 事件消费者（B2 设计 3.4 首批）：
 * - schema.migrated：动态对象发布 → SCHEMA 知识条目（替代发布流水线同步调用，可自动补齐）；
 * - object.record.created/.updated：动态记录摘要入库——AI 知识自动沉淀的正主；
 * - meta.published：预留（运行时缓存刷新），当前 ACK 即可。
 * 可靠语义：at-least-once + sys_event_consumed 幂等；异常 RECONSUME_LATER，
 * 超过 maxReconsumeTimes(3) 由 broker 转死信 %DLQ%openforge-knowledge。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "openforge.event.enabled", havingValue = "true")
public class KnowledgeEventConsumer extends AbstractEventConsumer
        implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final KnowledgeService knowledgeService;
    private final String namesrvAddr;
    private DefaultMQPushConsumer consumer;

    public KnowledgeEventConsumer(JdbcTemplate jdbc,
                                  com.fasterxml.jackson.databind.ObjectMapper mapper,
                                  KnowledgeService knowledgeService,
                                  @Value("${openforge.event.namesrv-addr:localhost:9876}") String namesrvAddr) {
        super(jdbc, mapper, "openforge-knowledge");
        this.knowledgeService = knowledgeService;
        this.namesrvAddr = namesrvAddr;
    }

    /** 公开消费入口（测试与桥接用）：解析 → 幂等占位 → 租户/MDC 回填。重复投递返回 empty。 */
    public java.util.Optional<EventEnvelope> consumeEnvelope(MessageExt msg) {
        return consume(msg);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            DefaultMQPushConsumer c = new DefaultMQPushConsumer("openforge-knowledge");
            c.setNamesrvAddr(namesrvAddr);
            c.setConsumeThreadMin(2);
            c.setConsumeThreadMax(5);
            c.setMaxReconsumeTimes(3);
            // 新消费组从头消费：知识自动沉淀不漏历史事件（可插拔向量库接入前的合理语义）
            c.setConsumeFromWhere(org.apache.rocketmq.common.consumer.ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            c.subscribe("openforge-meta", "schema.migrated || meta.published");
            c.subscribe("openforge-object", "object.record.created || object.record.updated");
            c.registerMessageListener((org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently)
                    this::onMessages);
            c.start();
            this.consumer = c;
            log.info("knowledge 事件消费者已启动: namesrv={}", namesrvAddr);
        } catch (Exception e) {
            log.error("knowledge 事件消费者启动失败（事件沉淀不可用，业务不受影响）: {}", e.getMessage());
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

    /** 业务分发（public 供 H2 单测直调；幂等在 consume 链路中由调用方保证）。 */
    public void handle(EventEnvelope env) {
        Map<String, Object> payload = env.getPayload();
        switch (env.getEventType()) {
            case "schema.migrated" -> knowledgeService.create(
                    "动态对象表结构：" + str(payload, "displayName") + "（" + str(payload, "tableName") + "）",
                    str(payload, "description"),
                    "schema,动态对象",
                    "SCHEMA",
                    str(payload, "objectKey"),
                    null);
            case "object.record.created", "object.record.updated" -> knowledgeService.create(
                    "记录摘要：" + str(payload, "displayName") + " #" + str(payload, "recordId"),
                    str(payload, "summary"),
                    "record," + str(payload, "objectKey"),
                    "RECORD",
                    str(payload, "objectKey") + "/" + str(payload, "recordId"),
                    null);
            case "meta.published" -> log.debug("meta.published 预留事件已 ACK: {}", str(payload, "objectKey"));
            default -> log.debug("未知事件类型已 ACK: {}", env.getEventType());
        }
        io.micrometer.core.instrument.Metrics.counter("openforge_events_consumed_total",
                "consumer", "openforge-knowledge", "type", env.getEventType()).increment();
        log.info("事件已消费: type={}, ref={}", env.getEventType(), env.getPayload().get("objectKey"));
    }

    /** 测试用上下文清理桥接（finish 为 protected，跨包不可及）。 */
    public void clearContext() {
        finish();
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}

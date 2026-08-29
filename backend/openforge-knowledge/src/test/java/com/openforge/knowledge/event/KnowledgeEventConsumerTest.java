package com.openforge.knowledge.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.event.EventEnvelope;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2-2 消费者业务与幂等（H2）：schema.migrated → SCHEMA 条目、object.record.created →
 * RECORD 摘要条目、消费链路对重复 eventId 幂等跳过。
 * 真实 broker 回路见 KnowledgeEventBrokerLoopTest。
 */
@SpringBootTest
class KnowledgeEventConsumerTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.openforge.knowledge.service.KnowledgeService knowledgeService;

    /** 测试上下文未开启 EVENT_ENABLED → 消费者 bean 不存在，手动构造（H2 只测业务与幂等）。 */
    private KnowledgeEventConsumer newConsumer() {
        return new KnowledgeEventConsumer(jdbc, new ObjectMapper(), knowledgeService, "localhost:9876");
    }

    private MessageExt messageOf(EventEnvelope env) throws Exception {
        MessageExt msg = new MessageExt();
        msg.setBody(new ObjectMapper().writeValueAsBytes(env));
        return msg;
    }

    @Test
    @DisplayName("schema.migrated → SCHEMA 条目（租户回填）；消费链路重复 eventId 幂等跳过")
    void schemaMigratedIdempotent() throws Exception {
        KnowledgeEventConsumer consumer = newConsumer();
        EventEnvelope env = new EventEnvelope(UUID.randomUUID().toString(), "schema.migrated", 1,
                "2026-08-29T12:00:00", "metadata", 7L, "trace-abc",
                Map.of("objectKey", "loop_obj", "displayName", "回路对象",
                        "tableName", "dyn_loop_obj", "description", "名称 name(STRING,必填)"));

        // 经 consume 链路（幂等占位 + 租户/MDC 回填）
        Optional<EventEnvelope> first = consumer.consumeEnvelope(messageOf(env));
        assertThat(first).isPresent();
        assertThat(com.openforge.common.tenant.TenantContext.getTenantId()).isEqualTo(7L);
        consumer.handle(first.get());
        consumer.clearContext();

        // 重复投递：幂等占位命中 → empty（不重复入库）
        assertThat(consumer.consumeEnvelope(messageOf(env))).isEmpty();

        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_item WHERE source_type='SCHEMA' AND source_ref='loop_obj'",
                Long.class);
        assertThat(cnt).isEqualTo(1);
    }

    @Test
    @DisplayName("object.record.created → RECORD 摘要条目")
    void recordCreatedSummary() {
        newConsumer().handle(new EventEnvelope(UUID.randomUUID().toString(), "object.record.created", 1,
                "2026-08-29T12:00:01", "metadata", 0L, null,
                Map.of("objectKey", "loop_obj", "displayName", "回路对象",
                        "recordId", 42, "summary", "name=CNC-01, location=一号车间")));
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_item WHERE source_type='RECORD' AND source_ref='loop_obj/42'",
                Long.class);
        assertThat(cnt).isEqualTo(1);
    }
}

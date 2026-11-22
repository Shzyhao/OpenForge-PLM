package com.openforge.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 事件消费骨架语义（v1.31 P0-1 修复回归）：at-least-once——失败补偿删幂等行、写入异常不误 ACK。 */
class AbstractEventConsumerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final AbstractEventConsumer consumer = new AbstractEventConsumer(jdbc, mapper, "test-consumer") {
    };

    private MessageExt msgOf(String json) {
        MessageExt msg = new MessageExt();
        msg.setBody(json.getBytes());
        return msg;
    }

    private String envelope(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"t\",\"eventVersion\":1,"
                + "\"producer\":\"p\",\"occurredAt\":\"2026-11-22T00:00:00Z\",\"tenantId\":0,\"payload\":{}}";
    }

    @Test
    @DisplayName("首投：幂等行落库后信封返回")
    void firstDeliveryPasses() {
        Optional<EventEnvelope> env = consumer.consume(msgOf(envelope(UUID.randomUUID().toString())));
        assertThat(env).isPresent();
    }

    @Test
    @DisplayName("重复投递：DuplicateKey 跳过（既有幂等语义防回归）")
    void duplicateSkipped() {
        // Mockito 5 varargs 语义：any() 在 varargs 位置吞整个数组致 matcher 错位——须全精确 eq
        doThrow(new DuplicateKeyException("dup")).when(jdbc).update(
                eq("INSERT INTO sys_event_consumed (event_id, consumer, consumed_at) VALUES (?, ?, CURRENT_TIMESTAMP)"),
                eq("e1"), eq("test-consumer"));
        assertThat(consumer.consume(msgOf(envelope("e1")))).isEmpty();
    }

    @Test
    @DisplayName("幂等行写入失败（DB 瞬断）：上抛交由 broker 重试，不误 ACK")
    void idempotencyInsertFailurePropagates() {
        doThrow(new IllegalStateException("db down")).when(jdbc).update(
                eq("INSERT INTO sys_event_consumed (event_id, consumer, consumed_at) VALUES (?, ?, CURRENT_TIMESTAMP)"),
                eq("e2"), eq("test-consumer"));
        assertThatThrownBy(() -> consumer.consume(msgOf(envelope("e2"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("业务失败：补偿删除幂等行后异常上抛（重投可再处理）")
    void handlerFailureRollsBackIdempotencyRow() {
        MessageExt msg = msgOf(envelope("e3"));
        RuntimeException boom = new RuntimeException("业务处理失败");
        assertThatThrownBy(() -> consumer.process(msg, env -> {
            throw boom;
        })).isSameAs(boom);
        verify(jdbc).update(eq("DELETE FROM sys_event_consumed WHERE event_id = ? AND consumer = ?"),
                eq("e3"), eq("test-consumer"));
    }

    @Test
    @DisplayName("补偿删除再失败：计数后仍上抛（可观测的残留丢失窗口）")
    void rollbackFailureStillPropagates() {
        doThrow(new IllegalStateException("db still down"))
                .when(jdbc).update(eq("DELETE FROM sys_event_consumed WHERE event_id = ? AND consumer = ?"),
                        eq("e4"), eq("test-consumer"));
        assertThatThrownBy(() -> consumer.process(msgOf(envelope("e4")), env -> {
            throw new RuntimeException("业务处理失败");
        })).hasMessage("业务处理失败");
    }

    @Test
    @DisplayName("载荷不可解析：ACK 丢弃（既有语义防回归）")
    void unparseableAcked() {
        assertThat(consumer.consume(msgOf("not-json"))).isEmpty();
    }
}

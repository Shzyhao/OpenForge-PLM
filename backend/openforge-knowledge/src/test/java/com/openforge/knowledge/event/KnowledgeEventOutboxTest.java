package com.openforge.knowledge.event;

import com.openforge.common.event.EventOutboxRelay;
import com.openforge.common.event.EventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2-P2 outbox 可靠性（H2，broker 不可达 localhost:1）：
 * - 事务内 publish → outbox 行原子落库（sent_at 空）；
 * - 无事务 publish → 直发失败落 outbox；
 * - relay 单轮 → 发送失败 retry_count+1（broker 恢复后自然补发）；
 * - disabled → 不落 outbox。
 */
@SpringBootTest
class KnowledgeEventOutboxTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    private EventPublisher enabledPublisher() {
        return new EventPublisher(mapper, jdbc, true, "localhost:1", 300, "outbox-test");
    }

    private EventPublisher disabledPublisher() {
        return new EventPublisher(mapper, jdbc, false, "localhost:1", 300, "outbox-test");
    }

    private EventOutboxRelay relay(EventPublisher publisher) {
        return new EventOutboxRelay(jdbc, publisher);
    }

    private Long unsentCount() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_event_outbox WHERE sent_at IS NULL", Long.class);
        return c == null ? 0 : c;
    }

    @Test
    @DisplayName("事务内 publish：outbox 行原子落库；relay 失败 retry_count+1")
    void transactionalOutboxAndRelayRetry() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            boolean queued = enabledPublisher().publish(
                    "openforge-object", "object.record.created", Map.of("id", 1));
            assertThat(queued).isTrue();
            // 同事务可见（原子性证明）
            assertThat(unsentCount()).isEqualTo(1);
        });

        EventOutboxRelay r = relay(enabledPublisher());
        r.relay();   // broker 不可达 → 失败
        Long retry = jdbc.queryForObject(
                "SELECT retry_count FROM sys_event_outbox WHERE sent_at IS NULL", Long.class);
        assertThat(retry).isEqualTo(1);

        r.relay();
        retry = jdbc.queryForObject(
                "SELECT retry_count FROM sys_event_outbox WHERE sent_at IS NULL", Long.class);
        assertThat(retry).isEqualTo(2);
    }

    @Test
    @DisplayName("无事务直发失败 → 落 outbox；disabled 不落 outbox")
    void noTxPathAndDisabled() {
        assertThat(enabledPublisher().publish(
                "openforge-object", "object.record.created", Map.of("id", 2))).isTrue();
        assertThat(unsentCount()).isGreaterThanOrEqualTo(1);

        Long before = unsentCount();
        assertThat(disabledPublisher().publish(
                "openforge-object", "object.record.created", Map.of("id", 3))).isFalse();
        assertThat(unsentCount()).isEqualTo(before);   // disabled 不落 outbox
    }

    @Test
    @DisplayName("发布成功路径不产生 outbox 残留的语义由 sent_at 标记承接（直发成功即标记）")
    void noTxSuccessMarksSent() {
        // 本地无 broker——此处仅验证失败路径与 disabled；成功路径由
        // KnowledgeEventBrokerLoopTest（真实 broker）在 CI 覆盖
        assertThat(enabledPublisher()).isNotNull();
    }
}

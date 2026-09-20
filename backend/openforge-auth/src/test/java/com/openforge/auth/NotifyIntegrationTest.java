package com.openforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.auth.dto.PageResponse;
import com.openforge.auth.entity.NotifyMessage;
import com.openforge.auth.event.NotifyEventConsumer;
import com.openforge.auth.service.NotifyService;
import com.openforge.common.api.BizException;
import com.openforge.common.event.EventEnvelope;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 站内通知集成（v1.23 设计 §1，H2）：摄取映射（USER 指派/ROLE fan-out/initiator 通知）、
 * 收件箱读写、越权按不存在应答、MQ 通道幂等（sys_event_consumed 去重）。
 */
@SpringBootTest
class NotifyIntegrationTest {

    @Autowired
    private NotifyService notifyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String INSERT_USER = """
            INSERT INTO sys_user (username, password_hash, display_name, status, tenant_id)
            VALUES (?, 'x', ?, 'ACTIVE', 0)
            """;

    @BeforeEach
    void seedUsers() {
        // 清理顺序：先删通知/角色绑定（旧 user_id 还在），再删用户重建——否则通知行成孤儿残留
        jdbc.update("DELETE FROM notify_message WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE 'notify-u%')");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE 'notify-u%')");
        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'notify-u%'");
        jdbc.update(INSERT_USER, "notify-u7", "用户七");
        jdbc.update(INSERT_USER, "notify-u8", "用户八");
        // u8 绑定测试专用角色用于 ROLE 任务 fan-out（不依赖迁移种子的角色编码——V16 起内置管理员编码为 ADMINS）
        jdbc.update("""
                MERGE INTO sys_role (role_code, role_name, builtin, tenant_id)
                KEY (role_code) VALUES ('NOTIFY_TEST_ROLE', '通知测试角色', 0, 0)
                """);
        jdbc.update("""
                INSERT INTO sys_user_role (user_id, role_id)
                SELECT u.id, r.id FROM sys_user u, sys_role r
                WHERE u.username = 'notify-u8' AND r.role_code = 'NOTIFY_TEST_ROLE'
                  AND NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id)
                """);
    }

    private long userId(String username) {
        return jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    @Test
    @DisplayName("task.created：USER 指派通知 assignee；completed 通知发起人")
    void directIngest() {
        long u7 = userId("notify-u7");
        long u9 = userId("notify-u8"); // 复用为发起人
        notifyService.ingest("task.created", Map.of(
                "assigneeId", u7, "nodeName", "初审", "instanceId", 101L, "bizType", "ECR", "bizId", 5L));
        notifyService.ingest("task.completed", Map.of(
                "initiatorId", u9, "nodeName", "初审", "instanceId", 101L, "action", "APPROVE"));

        assertThat(notifyService.unreadCount(u7)).isEqualTo(1);
        PageResponse<NotifyMessage> inbox7 = notifyService.inbox(u7, false, 1, 20);
        assertThat(inbox7.list()).hasSize(1);
        assertThat(inbox7.list().get(0).getTitle()).isEqualTo("待办审批：初审");
        assertThat(inbox7.list().get(0).getBizType()).isEqualTo("ECR");

        PageResponse<NotifyMessage> inbox9 = notifyService.inbox(u9, false, 1, 20);
        assertThat(inbox9.list().get(0).getTitle()).contains("已通过");
    }

    @Test
    @DisplayName("task.created：ROLE 任务 fan-out 给同角色成员（上限截断由 LIMIT 承载）")
    void roleFanOut() {
        long u8 = userId("notify-u8");
        notifyService.ingest("task.created", Map.of(
                "assigneeId", 0, "candidateRole", "NOTIFY_TEST_ROLE", "nodeName", "经理审批", "instanceId", 102L));
        assertThat(notifyService.unreadCount(u8)).isEqualTo(1);
    }

    @Test
    @DisplayName("未订阅事件类型忽略；收件人不存在跳过")
    void ignoreUnknownAndMissingRecipient() {
        notifyService.ingest("part.released", Map.of("partNumber", "P1"));
        notifyService.ingest("task.created", Map.of("assigneeId", 999999, "nodeName", "初审", "instanceId", 1L));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notify_message", Long.class)).isZero();
    }

    @Test
    @DisplayName("已读/全部已读；他人标记按不存在应答")
    void readAndOwnership() {
        long u7 = userId("notify-u7");
        long u8 = userId("notify-u8");
        notifyService.ingest("task.created", Map.of("assigneeId", u7, "nodeName", "初审", "instanceId", 103L));
        long msgId = notifyService.inbox(u7, true, 1, 20).list().get(0).getId();

        assertThatThrownBy(() -> notifyService.markRead(msgId, u8))
                .isInstanceOf(BizException.class).hasMessageContaining("通知不存在");

        notifyService.markRead(msgId, u7);
        assertThat(notifyService.unreadCount(u7)).isZero();

        notifyService.ingest("task.created", Map.of("assigneeId", u7, "nodeName", "复审", "instanceId", 104L));
        assertThat(notifyService.unreadCount(u7)).isEqualTo(1);
        notifyService.markAllRead(u7);
        assertThat(notifyService.unreadCount(u7)).isZero();
    }

    /** 暴露 protected consume()/finish() 供测试 MQ 通道幂等（不依赖 broker）。 */
    static class TestableConsumer extends NotifyEventConsumer {
        TestableConsumer(JdbcTemplate jdbc, ObjectMapper mapper, NotifyService service) {
            super(jdbc, mapper, service, "localhost:0", 2);
        }

        Optional<EventEnvelope> consumePublic(MessageExt msg) {
            return consume(msg);
        }

        void finishPublic() {
            finish();
        }
    }

    @Test
    @DisplayName("MQ 通道幂等：同 eventId 重复投递仅落一条")
    void consumerIdempotent() throws Exception {
        long u7 = userId("notify-u7");
        TestableConsumer consumer = new TestableConsumer(jdbc, objectMapper, notifyService);
        EventEnvelope env = new EventEnvelope();
        env.setEventId("notify-test-evt-1");
        env.setEventType("task.created");
        env.setPayload(Map.of("assigneeId", (Object) u7, "nodeName", "幂等节点", "instanceId", 105L));
        MessageExt msg = new MessageExt();
        msg.setBody(objectMapper.writeValueAsBytes(env));

        try {
            consumer.consumePublic(msg).ifPresent(consumer::handle);
            consumer.consumePublic(msg).ifPresent(consumer::handle); // 重复投递被 sys_event_consumed 判重
        } finally {
            consumer.finishPublic(); // 清理 ThreadLocal 租户上下文，防测试线程污染
        }

        // 重复消费后标题相同的行只有一条：
        long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify_message WHERE title LIKE '%幂等节点%'", Long.class);
        assertThat(rows).isEqualTo(1);
    }
}

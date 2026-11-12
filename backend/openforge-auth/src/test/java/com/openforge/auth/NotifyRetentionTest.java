package com.openforge.auth;

import com.openforge.auth.service.NotifyRetentionJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知保留期清理（v1.24 设计 §3，H2）：只清已读超期行；未读与新行保留；≤0 天关闭。
 * 原生 JDBC 绕租户拦截器（后台线程租户恒 0），故三租户混插数据全量可比对。
 */
@SpringBootTest
class NotifyRetentionTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notify_message WHERE title LIKE 'RET-%'");
    }

    /** 同名 H2 跨测试类共享：RET-% 残行会污染 NotifyIntegrationTest 的全表断言（类序平台不同） */
    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notify_message WHERE title LIKE 'RET-%'");
    }

    private void insert(String title, int tenantId, boolean read, int ageDays) {
        LocalDateTime t = LocalDateTime.now().minusDays(ageDays);
        jdbc.update("INSERT INTO notify_message (tenant_id, user_id, event_type, title, read_flag, created_at) "
                        + "VALUES (?, 1, 'task.created', ?, ?, ?)",
                tenantId, title, read ? 1 : 0, Timestamp.valueOf(t));
    }

    @Test
    @DisplayName("仅清理超期已读；未读与新行保留；多租户全量覆盖")
    void purgeOnlyExpiredRead() {
        insert("RET-old-read", 0, true, 100);
        insert("RET-old-unread", 1, false, 100);
        insert("RET-new-read", 2, true, 1);

        NotifyRetentionJob job = new NotifyRetentionJob(jdbc, 90);
        job.cleanup();

        Long remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify_message WHERE title LIKE 'RET-%'", Long.class);
        assertThat(remain).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify_message WHERE title = 'RET-old-read'", Long.class)).isZero();
    }

    @Test
    @DisplayName("保留天数 ≤0 时作业关闭")
    void disabledWhenNonPositive() {
        insert("RET-keep", 0, true, 100);
        new NotifyRetentionJob(jdbc, 0).cleanup();
        new NotifyRetentionJob(jdbc, -1).cleanup();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notify_message WHERE title = 'RET-keep'", Long.class)).isEqualTo(1L);
    }
}

package com.openforge.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知保留期清理（v1.24 设计 §3，LogRetentionJob 同规）：每日删除超过保留期的**已读**通知，
 * 未读永不清理（用户权利优先于存储成本）。分批（500/批）避免大表长事务；保留天数 ≤0 = 关闭。
 * 原生 JDBC 绕开租户拦截器——后台线程 TenantContext 恒为 0，走 mapper 会只清理平台租户行
 * （LogRetentionJob 因表在 GLOBAL_TABLES 无此问题，notify_message 有租户列故必须绕行）。
 */
@Component
public class NotifyRetentionJob {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final long retentionDays;

    public NotifyRetentionJob(JdbcTemplate jdbc,
                              @Value("${openforge.notify.retention-days:90}") long retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${openforge.notify.retention-cron:0 40 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deleted = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM notify_message WHERE read_flag = 1 AND created_at < ? LIMIT " + BATCH_SIZE,
                    Long.class, cutoff);
            if (ids.isEmpty()) {
                break;
            }
            jdbc.update("DELETE FROM notify_message WHERE id IN ("
                    + ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")");
            deleted += ids.size();
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(NotifyRetentionJob.class)
                    .info("notify retention cleanup: days={} deleted={}", retentionDays, deleted);
        }
    }
}

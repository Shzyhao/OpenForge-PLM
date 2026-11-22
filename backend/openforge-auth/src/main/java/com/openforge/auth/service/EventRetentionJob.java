package com.openforge.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 事件双表保留期清理（v1.31 P1-8，系统结构复盘遗留）：sys_event_outbox / sys_event_consumed
 * 无界增长（其余五类日志/死信表均有保留任务，唯事件双表漏网）——长期运行后索引膨胀、
 * 去重 INSERT 变慢。
 * 两表建表于 auth 迁移（V23 事件基建）且为 GLOBAL 表，auth 为表 owner 承担清理；
 * JdbcTemplate 直写（两表无 MP 实体）。分批 500 选删避免大表长事务锁（对齐 LogRetentionJob）。
 * 语义边界：outbox 仅删已发送行（sent_at 非空且超期）——未发送/待补发行不删；
 * consumed 超期全删（去重窗口=保留期，MQ 重投窗口远小于保留期，安全）。
 * 保留天数 0/负值 = 关闭。单实例语义同 LogRetentionJob（auth 不水平扩缩）。
 */
@Component
public class EventRetentionJob {

    private static final int BATCH_SIZE = 500;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final long retentionDays;

    public EventRetentionJob(JdbcTemplate jdbc,
                             @Value("${openforge.event.retention-days:30}") long retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${openforge.event.retention-cron:0 50 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long outboxDeleted = purgeByTime(
                "SELECT id FROM sys_event_outbox WHERE sent_at IS NOT NULL AND sent_at < ? LIMIT " + BATCH_SIZE,
                "DELETE FROM sys_event_outbox WHERE id IN", cutoff);
        long consumedDeleted = purgeByTime(
                "SELECT id FROM sys_event_consumed WHERE consumed_at < ? LIMIT " + BATCH_SIZE,
                "DELETE FROM sys_event_consumed WHERE id IN", cutoff);
        if (outboxDeleted > 0 || consumedDeleted > 0) {
            org.slf4j.LoggerFactory.getLogger(EventRetentionJob.class)
                    .info("event retention cleanup: days={} outboxDeleted={} consumedDeleted={}",
                            retentionDays, outboxDeleted, consumedDeleted);
        }
    }

    private long purgeByTime(String selectSql, String deletePrefix, LocalDateTime cutoff) {
        long deleted = 0;
        while (true) {
            java.util.List<java.lang.Number> ids = jdbc.queryForList(selectSql, Number.class, cutoff);
            if (ids.isEmpty()) {
                return deleted;
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            java.util.List<Object> args = new java.util.ArrayList<>(ids);
            deleted += jdbc.update(deletePrefix + " (" + placeholders + ")", args.toArray());
            if (ids.size() < BATCH_SIZE) {
                return deleted;
            }
        }
    }
}

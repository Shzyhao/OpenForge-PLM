package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.connector.entity.ConnExecLog;
import com.openforge.connector.mapper.ConnExecLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行日志/死信保留期清理（对齐登录日志 180 天惯例，评审确认；技术债「无界增长」先例 v1.9.0）：
 * 每日 03:45 分批（500/批）选删避免大表长事务锁；保留天数 0/负值 = 关闭。
 * P2-2 起：DLQ 仅清理终态（RESOLVED/DISCARDED），PENDING 保留待人工处理。
 * 调度由 common ModuleSchedulingConfig(@EnableScheduling) 提供（包扫描生效）。
 */
@Component
public class ExecLogRetentionJob {

    private static final int BATCH_SIZE = 500;

    private final ConnExecLogMapper execLogMapper;
    private final com.openforge.connector.mapper.ConnTriggerDlqMapper dlqMapper;
    private final long retentionDays;

    public ExecLogRetentionJob(ConnExecLogMapper execLogMapper,
                               com.openforge.connector.mapper.ConnTriggerDlqMapper dlqMapper,
                               @Value("${openforge.connector.log-retention-days:180}") long retentionDays) {
        this.execLogMapper = execLogMapper;
        this.dlqMapper = dlqMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${openforge.connector.log-retention-cron:0 45 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long deleted = cleanupExecLogs(cutoff);
        deleted += cleanupDlq(cutoff);
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(ExecLogRetentionJob.class)
                    .info("connector retention cleanup: days={} deleted={}", retentionDays, deleted);
        }
    }

    private long cleanupExecLogs(LocalDateTime cutoff) {
        long deleted = 0;
        while (true) {
            List<Long> ids = execLogMapper.selectList(new LambdaQueryWrapper<ConnExecLog>()
                            .select(ConnExecLog::getId)
                            .lt(ConnExecLog::getCreatedAt, cutoff)
                            .last("LIMIT " + BATCH_SIZE))
                    .stream().map(ConnExecLog::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            execLogMapper.deleteByIds(ids);
            deleted += ids.size();
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }

    /** 死信终态清理：RESOLVED/DISCARDED 且 replayed_at 早于保留界（PENDING 永不自动删）。 */
    private long cleanupDlq(LocalDateTime cutoff) {
        long deleted = 0;
        while (true) {
            List<Long> ids = dlqMapper.selectList(new LambdaQueryWrapper<com.openforge.connector.entity.ConnTriggerDlq>()
                            .select(com.openforge.connector.entity.ConnTriggerDlq::getId)
                            .in(com.openforge.connector.entity.ConnTriggerDlq::getStatus, "RESOLVED", "DISCARDED")
                            .lt(com.openforge.connector.entity.ConnTriggerDlq::getReplayedAt, cutoff)
                            .last("LIMIT " + BATCH_SIZE))
                    .stream().map(com.openforge.connector.entity.ConnTriggerDlq::getId).toList();
            if (ids.isEmpty()) {
                break;
            }
            dlqMapper.deleteByIds(ids);
            deleted += ids.size();
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        return deleted;
    }
}

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
 * 执行日志保留期清理（对齐登录日志 180 天惯例，评审确认；技术债「无界增长」先例 v1.9.0）：
 * 每日 03:45 分批（500/批）选删避免大表长事务锁；保留天数 0/负值 = 关闭。
 * 调度由 common ModuleSchedulingConfig(@EnableScheduling) 提供（包扫描生效）。
 */
@Component
public class ExecLogRetentionJob {

    private static final int BATCH_SIZE = 500;

    private final ConnExecLogMapper execLogMapper;
    private final long retentionDays;

    public ExecLogRetentionJob(ConnExecLogMapper execLogMapper,
                               @Value("${openforge.connector.log-retention-days:180}") long retentionDays) {
        this.execLogMapper = execLogMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${openforge.connector.log-retention-cron:0 45 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
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
        if (deleted > 0) {
            org.slf4j.LoggerFactory.getLogger(ExecLogRetentionJob.class)
                    .info("connector exec_log retention cleanup: days={} deleted={}", retentionDays, deleted);
        }
    }
}

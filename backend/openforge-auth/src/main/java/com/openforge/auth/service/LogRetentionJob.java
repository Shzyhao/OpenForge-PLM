package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysAuditLog;
import com.openforge.auth.entity.SysLoginLog;
import com.openforge.auth.mapper.AuditLogMapper;
import com.openforge.auth.mapper.LoginLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录/审计日志保留期清理（技术债「日志表无界增长」）：每日 03:30 删除超过保留期的记录。
 * 分批（500/批）选删避免大表长事务锁；保留天数 0/负值 = 关闭。
 * 两表在 GLOBAL_TABLES（无租户过滤），后台线程全租户清理无需 TenantContext。
 * 单实例语义：auth 服务不水平扩缩（模块注册表单实例），无分布式锁需求。
 */
@Component
public class LogRetentionJob {

    private static final int BATCH_SIZE = 500;

    private final LoginLogMapper loginLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final long retentionDays;

    public LogRetentionJob(LoginLogMapper loginLogMapper, AuditLogMapper auditLogMapper,
                           @Value("${openforge.security.log-retention-days:180}") long retentionDays) {
        this.loginLogMapper = loginLogMapper;
        this.auditLogMapper = auditLogMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${openforge.security.log-retention-cron:0 30 3 * * *}")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long loginDeleted = purgeLogin(cutoff);
        long auditDeleted = purgeAudit(cutoff);
        if (loginDeleted > 0 || auditDeleted > 0) {
            org.slf4j.LoggerFactory.getLogger(LogRetentionJob.class)
                    .info("log retention cleanup: days={} loginDeleted={} auditDeleted={}",
                            retentionDays, loginDeleted, auditDeleted);
        }
    }

    private long purgeLogin(LocalDateTime cutoff) {
        long deleted = 0;
        while (true) {
            List<Long> ids = loginLogMapper.selectList(new LambdaQueryWrapper<SysLoginLog>()
                            .select(SysLoginLog::getId)
                            .lt(SysLoginLog::getCreatedAt, cutoff)
                            .last("LIMIT " + BATCH_SIZE))
                    .stream().map(SysLoginLog::getId).toList();
            if (ids.isEmpty()) {
                return deleted;
            }
            loginLogMapper.deleteByIds(ids);
            deleted += ids.size();
            if (ids.size() < BATCH_SIZE) {
                return deleted;
            }
        }
    }

    private long purgeAudit(LocalDateTime cutoff) {
        long deleted = 0;
        while (true) {
            List<Long> ids = auditLogMapper.selectList(new LambdaQueryWrapper<SysAuditLog>()
                            .select(SysAuditLog::getId)
                            .lt(SysAuditLog::getCreatedAt, cutoff)
                            .last("LIMIT " + BATCH_SIZE))
                    .stream().map(SysAuditLog::getId).toList();
            if (ids.isEmpty()) {
                return deleted;
            }
            auditLogMapper.deleteByIds(ids);
            deleted += ids.size();
            if (ids.size() < BATCH_SIZE) {
                return deleted;
            }
        }
    }
}

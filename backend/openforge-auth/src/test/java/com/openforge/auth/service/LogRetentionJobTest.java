package com.openforge.auth.service;

import com.openforge.auth.entity.SysAuditLog;
import com.openforge.auth.entity.SysLoginLog;
import com.openforge.auth.mapper.AuditLogMapper;
import com.openforge.auth.mapper.LoginLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 日志保留期清理（H2）：超期删除/近期保留/天数 0 关闭语义。 */
@SpringBootTest
class LogRetentionJobTest {

    @Autowired
    private LogRetentionJob job;

    @Autowired
    private LoginLogMapper loginLogMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    private long insertLogin(LocalDateTime createdAt) {
        SysLoginLog row = new SysLoginLog();
        row.setUsername("retention-user");
        row.setSuccess(1);
        row.setReason("OK");
        row.setIp("127.0.0.1");
        row.setUserAgent("retention-test");
        row.setCreatedAt(createdAt);
        loginLogMapper.insert(row);
        return row.getId();
    }

    private long insertAudit(LocalDateTime createdAt) {
        SysAuditLog row = new SysAuditLog();
        row.setOperatorId(1L);
        row.setAction("USER_CREATE");
        row.setTargetType("USER");
        row.setTargetId("9");
        row.setDetail("retention-test");
        row.setCreatedAt(createdAt);
        auditLogMapper.insert(row);
        return row.getId();
    }

    @Test
    @DisplayName("默认保留期（180 天）：超期行被删、近期行保留，两表同样生效")
    void cleanupRemovesOnlyExpiredRows() {
        long oldLogin = insertLogin(LocalDateTime.now().minusDays(365));
        long recentLogin = insertLogin(LocalDateTime.now().minusDays(1));
        long oldAudit = insertAudit(LocalDateTime.now().minusDays(365));
        long recentAudit = insertAudit(LocalDateTime.now().minusDays(1));

        job.cleanup();

        assertThat(loginLogMapper.selectById(oldLogin)).isNull();
        assertThat(loginLogMapper.selectById(recentLogin)).isNotNull();
        assertThat(auditLogMapper.selectById(oldAudit)).isNull();
        assertThat(auditLogMapper.selectById(recentAudit)).isNotNull();
    }

    @Test
    @DisplayName("保留天数 0 = 关闭：超期行不被删除")
    void disabledWhenRetentionDaysZero() {
        long oldLogin = insertLogin(LocalDateTime.now().minusDays(365));
        try {
            new LogRetentionJob(loginLogMapper, auditLogMapper, 0).cleanup();
            assertThat(loginLogMapper.selectById(oldLogin)).isNotNull();
        } finally {
            loginLogMapper.deleteById(oldLogin);
        }
    }
}

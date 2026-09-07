package com.openforge.connector.service;

import com.openforge.common.tenant.TenantContext;
import com.openforge.connector.entity.ConnDefinition;
import com.openforge.connector.mapper.ConnDefinitionMapper;
import com.openforge.connector.spec.TriggerSpecs;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * CRON 触发调度器（P2-2 §12.2）：周期重同步已发布 CRON 连接器（跨租户全量，
 * 绕过租户行级过滤），按 cron+版本签名增量重排——发布/停用/改表达式后至多一个
 * 重同步周期生效。执行线程逐连接器回填租户上下文。
 */
@Slf4j
@Component
public class CronTriggerScheduler {

    private final ConnDefinitionMapper definitionMapper;
    private final TriggerDispatcher dispatcher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ThreadPoolTaskScheduler scheduler;

    /** key = tenantId:connId；value 签名 = cron + version（变更即重排）。 */
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();

    public CronTriggerScheduler(ConnDefinitionMapper definitionMapper,
                                TriggerDispatcher dispatcher,
                                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                @Value("${openforge.connector.trigger.cron-pool-size:2}") int poolSize) {
        this.definitionMapper = definitionMapper;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(Math.max(1, poolSize));
        s.setThreadNamePrefix("conn-cron-");
        s.setWaitForTasksToCompleteOnShutdown(false);
        s.initialize();
        this.scheduler = s;
    }

    /** 重同步（默认 30s；@EnableScheduling 由 common ModuleSchedulingConfig 提供）。 */
    @Scheduled(initialDelayString = "${openforge.connector.trigger.resync-initial-ms:8000}",
            fixedDelayString = "${openforge.connector.trigger.resync-ms:30000}")
    public void resync() {
        try {
            List<ConnDefinition> crons = definitionMapper.selectPublishedByTriggerType(TriggerSpecs.TYPE_CRON);
            Map<String, String> latest = new HashMap<>();
            for (ConnDefinition def : crons) {
                TriggerSpecs.TriggerConfig cfg = TriggerSpecs.parseRuntime(
                        def.getTriggerType(), def.getTriggerJson(), objectMapper);
                if (!TriggerSpecs.TYPE_CRON.equals(cfg.type()) || cfg.cron().isEmpty()) {
                    log.warn("CRON 触发配置非法已跳过: connCode={}", def.getConnCode());
                    continue;
                }
                String key = def.getTenantId() + ":" + def.getId();
                String signature = cfg.cron() + "@v" + def.getCurrentVersion();
                latest.put(key, signature);
                if (signature.equals(signatures.get(key))) {
                    continue;
                }
                cancelTask(key);
                signatures.put(key, signature);
                scheduleNext(key, signature, def, cfg);
                log.info("CRON 触发已注册: connCode={}, cron={}, tenant={}",
                        def.getConnCode(), cfg.cron(), def.getTenantId());
            }
            // 停用/删除/降级触发：摘除调度
            for (String key : tasks.keySet()) {
                if (!latest.containsKey(key)) {
                    cancelTask(key);
                    signatures.remove(key);
                }
            }
        } catch (Exception e) {
            log.error("CRON 触发重同步失败（下轮重试）", e);
        }
    }

    private void cancelTask(String key) {
        ScheduledFuture<?> old = tasks.remove(key);
        if (old != null) {
            old.cancel(false);
        }
    }

    /** 自续链调度：执行完算下一次；签名被重同步替换/摘除时链路自然终止。 */
    private void scheduleNext(String key, String signature, ConnDefinition def,
                              TriggerSpecs.TriggerConfig cfg) {
        java.time.ZonedDateTime next = org.springframework.scheduling.support.CronExpression
                .parse(cfg.cron()).next(java.time.ZonedDateTime.now());
        if (next == null) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                fire(def, cfg);
            } finally {
                if (signature.equals(signatures.get(key))) {
                    scheduleNext(key, signature, def, cfg);
                }
            }
        }, next.toInstant());
        tasks.put(key, future);
    }

    private void fire(ConnDefinition def, TriggerSpecs.TriggerConfig cfg) {
        TenantContext.setTenantId(def.getTenantId());
        MDC.put(com.openforge.common.trace.TraceIdFilter.MDC_KEY,
                "cron-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            dispatcher.executeTrigger(def, cfg.params(), TriggerSpecs.TYPE_CRON,
                    LocalDateTime.now().toString());
        } finally {
            TenantContext.clear();
            MDC.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        tasks.values().forEach(f -> f.cancel(false));
        scheduler.shutdown();
    }
}

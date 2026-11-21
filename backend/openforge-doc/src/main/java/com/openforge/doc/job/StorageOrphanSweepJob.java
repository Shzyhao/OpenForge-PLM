package com.openforge.doc.job;

import com.openforge.doc.storage.StorageClient;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 存储孤儿清扫（v1.27 P2#10）：purge 的文件字节 GC 是 best-effort，失败后存储侧永久残留孤儿。
 * 每周对账：存储列举 keys vs doc_file.storage_key 全集——删除存储侧孤儿（无行引用的文件）；
 * 反向差集（悬空行记录）只报告不删（防误删元数据）。存储实现不支持列举时跳过。
 * 单实例语义：doc 服务不水平扩缩（对齐 auth/LogRetentionJob 先例）。
 */
@Slf4j
@Component
public class StorageOrphanSweepJob {

    private final StorageClient storageClient;
    private final JdbcTemplate jdbc;

    public StorageOrphanSweepJob(StorageClient storageClient, JdbcTemplate jdbc) {
        this.storageClient = storageClient;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${openforge.doc.orphan-sweep-cron:0 10 4 * * SUN}")
    public void sweep() {
        List<String> keys;
        try {
            keys = storageClient.listAllKeys();
        } catch (UnsupportedOperationException e) {
            log.debug("存储实现不支持列举，孤儿清扫跳过");
            return;
        } catch (Exception e) {
            log.warn("存储列举失败，本轮孤儿清扫跳过: {}", e.getMessage());
            return;
        }
        Set<String> referenced = new HashSet<>(jdbc.queryForList("SELECT storage_key FROM doc_file", String.class));
        int deleted = 0;
        for (String key : keys) {
            if (referenced.contains(key)) {
                continue;
            }
            try {
                storageClient.delete(key);
                deleted++;
            } catch (Exception e) {
                log.warn("孤儿文件删除失败: key={} — {}", key, e.getMessage());
            }
        }
        long dangling = referenced.stream().filter(r -> !keys.contains(r)).count();
        log.info("doc 孤儿清扫完成: 存储文件={} 行引用={} 删除孤儿={} 悬空记录={}(只报告)",
                keys.size(), referenced.size(), deleted, dangling);
        Metrics.counter("openforge_orphan_swept_total", "domain", "doc", "side", "file").increment(deleted);
        Metrics.counter("openforge_orphan_dangling_total", "domain", "doc").increment(dangling);
    }
}

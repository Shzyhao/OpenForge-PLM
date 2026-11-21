package com.openforge.knowledge.job;

import com.openforge.knowledge.service.VectorStore;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 向量孤儿清扫（v1.27 P2#10）：purgeItem 的向量移除是 best-effort，失败后向量存储侧永久残留。
 * 每周对账：向量存储全部 id vs knowledge_item.vector_id 引用集——删除无引用的孤儿向量；
 * 反向差集（悬空引用）只报告不清洗（源数据问题留给业务侧处理）。
 * 内存实现为重启自清（语义上无孤儿），pgvector 实现为收益主体。
 * 单实例语义：knowledge 服务不水平扩缩（对齐 auth/LogRetentionJob 先例）。
 */
@Slf4j
@Component
public class VectorOrphanSweepJob {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbc;

    public VectorOrphanSweepJob(VectorStore vectorStore, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${openforge.knowledge.orphan-sweep-cron:0 30 4 * * SUN}")
    public void sweep() {
        List<String> ids;
        try {
            ids = vectorStore.listAllIds();
        } catch (UnsupportedOperationException e) {
            log.debug("向量存储不支持列举，孤儿清扫跳过");
            return;
        } catch (Exception e) {
            log.warn("向量存储列举失败，本轮孤儿清扫跳过: {}", e.getMessage());
            return;
        }
        Set<String> referenced = new HashSet<>(jdbc.queryForList(
                "SELECT vector_id FROM knowledge_item WHERE vector_id IS NOT NULL", String.class));
        int removed = 0;
        for (String id : ids) {
            if (referenced.contains(id)) {
                continue;
            }
            try {
                vectorStore.remove(id);
                removed++;
            } catch (Exception e) {
                log.warn("孤儿向量删除失败: vectorId={} — {}", id, e.getMessage());
            }
        }
        long dangling = referenced.stream().filter(r -> !ids.contains(r)).count();
        log.info("knowledge 向量孤儿清扫完成: 存储向量={} 引用={} 删除孤儿={} 悬空引用={}(只报告)",
                ids.size(), referenced.size(), removed, dangling);
        Metrics.counter("openforge_orphan_swept_total", "domain", "knowledge", "side", "vector").increment(removed);
        Metrics.counter("openforge_orphan_dangling_total", "domain", "knowledge").increment(dangling);
    }
}

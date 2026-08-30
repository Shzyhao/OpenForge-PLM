package com.openforge.metadata.service;

import com.openforge.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 发布元数据 TTL 缓存：动态记录运行时每请求两次元数据查询（对象 + 字段），
 * 元数据读多写极少，短 TTL + 变更驱逐即可承接（技术债「动态元数据 TTL 缓存」）。
 *
 * 键带租户（meta_object/meta_field 走行级租户拦截器）；驱逐在发布事务 afterCommit
 * 执行（MetaPublishService），TTL 30s 兜底界外 DB 变更的可见性延迟。
 * 上界 500 条（性能门内存上界）：超出先清过期，仍超则整体清空——元数据对象量级远低于此。
 */
@Component
public class PublishedMetaCache {

    private record Entry(PublishedMeta meta, long expiresAtMillis) {
    }

    static final int MAX_ENTRIES = 500;

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public PublishedMetaCache(
            @Value("${openforge.metadata.cache-ttl-seconds:30}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** 命中返回缓存快照；未命中/过期/关闭（ttl<=0）返回 null。 */
    public PublishedMeta get(String objectKey) {
        if (ttlMillis <= 0) {
            return null;
        }
        Entry entry = cache.get(key(objectKey));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAtMillis()) {
            cache.remove(key(objectKey));
            return null;
        }
        return entry.meta();
    }

    public void put(String objectKey, PublishedMeta meta) {
        if (ttlMillis <= 0) {
            return;
        }
        String key = key(objectKey);
        if (cache.size() >= MAX_ENTRIES && cache.keySet().stream().noneMatch(k -> k.equals(key))) {
            purgeExpired();
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(meta, System.currentTimeMillis() + ttlMillis));
    }

    /** 变更驱逐（仅当前租户的该对象）。 */
    public void evict(String objectKey) {
        cache.remove(key(objectKey));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now > e.getValue().expiresAtMillis());
    }

    private String key(String objectKey) {
        return TenantContext.getTenantId() + "|" + objectKey;
    }
}

package com.openforge.connector.service;

import com.openforge.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 已发布连接器 TTL 缓存（仿 metadata PublishedMetaCache，v1.9.0 已验证模式）：
 * 键带租户；发布事务 afterCommit 放入/停用驱逐；TTL 30s 兜底界外变更可见性。
 * 上界 500 条：超出先清过期，仍超则整体清空。
 */
@Component
public class PublishedConnCache {

    private record Entry(PublishedConn conn, long expiresAtMillis) {
    }

    static final int MAX_ENTRIES = 500;

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public PublishedConnCache(
            @Value("${openforge.connector.cache-ttl-seconds:30}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    public PublishedConn get(String connCode) {
        if (ttlMillis <= 0) {
            return null;
        }
        Entry entry = cache.get(key(connCode));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAtMillis()) {
            cache.remove(key(connCode));
            return null;
        }
        return entry.conn();
    }

    public void put(PublishedConn conn) {
        if (ttlMillis <= 0) {
            return;
        }
        String key = key(conn.connCode());
        if (cache.size() >= MAX_ENTRIES && cache.keySet().stream().noneMatch(k -> k.equals(key))) {
            purgeExpired();
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(conn, System.currentTimeMillis() + ttlMillis));
    }

    public void evict(String connCode) {
        cache.remove(key(connCode));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now > e.getValue().expiresAtMillis());
    }

    private String key(String connCode) {
        return TenantContext.getTenantId() + "|" + connCode;
    }
}

package com.openforge.security;

import com.openforge.common.api.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * auth 服务权限查询客户端：带 TTL 内存缓存。
 * 权限变更的最长生效延迟 = cacheTtlSeconds（默认 60s）。
 */
public class PermissionQueryClient {

    private record CachedEntry(PermissionView view, long expiresAt) {
    }

    private static final ParameterizedTypeReference<ApiResponse<PermissionView>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String internalToken;
    private final long cacheTtlMillis;
    private final Map<Long, CachedEntry> cache = new ConcurrentHashMap<>();
    /** 容量上界：长期运行防无界增长（大量一次性用户 id 场景）。触顶先清过期，再全清。 */
    private static final int MAX_CACHE_ENTRIES = 2048;

    public PermissionQueryClient(OpenForgeSecurityProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .defaultHeader("X-Internal-Token", properties.getInternalToken())
                .build();
        this.internalToken = properties.getInternalToken();
        this.cacheTtlMillis = properties.getCacheTtlSeconds() * 1000;
    }

    public PermissionView fetch(Long userId) {
        CachedEntry entry = cache.get(userId);
        if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
            return entry.view();
        }
        ApiResponse<PermissionView> response = restClient.get()
                .uri("/api/v1/internal/permissions/{userId}", userId)
                .retrieve()
                .body(TYPE);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new IllegalStateException("权限查询失败: userId=" + userId);
        }
        PermissionView view = response.getData();
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.values().removeIf(e -> e.expiresAt() <= now);
            cache.clear();   // 仍超限（缓存全为热条目）：全清代价仅一轮 auth 回源
        }
        cache.put(userId, new CachedEntry(view, System.currentTimeMillis() + cacheTtlMillis));
        return view;
    }

    /** 权限变更后主动失效（预留给管理端联动）。 */
    public void evict(Long userId) {
        cache.remove(userId);
    }
}

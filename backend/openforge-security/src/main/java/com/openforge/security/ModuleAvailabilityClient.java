package com.openforge.security;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块可用性客户端（A4 设计 3.4）：服务间调用前的 ensureAvailable 前置检查，
 * 依赖模块未启用/损坏时返回明确语义（4022/4023）而非裸连接错误。
 * 30s 内存缓存——与网关路由轮询同节奏。
 */
public class ModuleAvailabilityClient {

    private record CachedEntry(String status, long expiresAt) {
    }

    private static final ParameterizedTypeReference<ApiResponse<Map<String, String>>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String internalToken;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public ModuleAvailabilityClient(OpenForgeSecurityProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAuthBaseUrl())
                .build();
        this.internalToken = properties.getInternalToken();
    }

    /** 依赖模块须已注册且启用；查询失败按可用处理（注册中心故障不应放大为业务不可用）。 */
    public void ensureAvailable(String moduleKey) {
        String status = statusOf(moduleKey);
        if ("NOT_FOUND".equals(status)) {
            throw new BizException(ErrorCode.MODULE_NOT_FOUND, "依赖模块未注册: " + moduleKey);
        }
        if (!"ENABLED".equals(status)) {
            throw new BizException(ErrorCode.MODULE_DISABLED,
                    "依赖模块未启用（请联系管理员）: " + moduleKey + " [" + status + "]");
        }
    }

    private String statusOf(String moduleKey) {
        CachedEntry entry = cache.get(moduleKey);
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAt() > now) {
            return entry.status();
        }
        String status;
        try {
            ApiResponse<Map<String, String>> response = restClient.get()
                    .uri("/api/v1/internal/modules/status/{moduleKey}", moduleKey)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(TYPE);
            status = response != null && response.getData() != null
                    ? response.getData().getOrDefault("status", "NOT_FOUND") : "NOT_FOUND";
        } catch (Exception e) {
            return "ENABLED";   // 注册中心不可达：降级放行，由真实调用暴露错误
        }
        cache.put(moduleKey, new CachedEntry(status, now + 30_000));
        return status;
    }

    /** 模块启停后主动失效（预留管理端联动）。 */
    public void evict(String moduleKey) {
        cache.remove(moduleKey);
    }
}

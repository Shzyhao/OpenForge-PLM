package com.openforge.gateway.modules;

import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态路由计划（A4 设计 3.3，纯函数便于单测）：
 * 输入注册中心模块清单 → 输出应生效的 RouteDefinition + 自检结果
 * （缺失前缀/心跳过期模块）。停用即摘除：仅 ENABLED 模块参与。
 */
public final class ModuleRoutePlan {

    /** 注册中心视图（auth GET /internal/modules 的行投影）。 */
    public record ModuleView(String moduleKey, String moduleType, String status,
                             List<String> routes, String serviceUri, LocalDateTime heartbeatAt) {
    }

    public record Plan(List<RouteDefinition> definitions, List<String> missingRoutes,
                       List<String> staleModules) {
    }

    /** 心跳超时 = 3 × 60s 注册周期（A4 设计 3.2；EXTENSION 无进程不参与心跳）。 */
    static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(180);

    private ModuleRoutePlan() {
    }

    public static Plan compute(List<ModuleView> modules, LocalDateTime now) {
        List<RouteDefinition> definitions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> stale = new ArrayList<>();

        for (ModuleView module : modules) {
            if (!"ENABLED".equals(module.status())) {
                continue;
            }
            boolean extension = "EXTENSION".equals(module.moduleType());
            if (!extension && (module.heartbeatAt() == null
                    || module.heartbeatAt().plus(HEARTBEAT_TIMEOUT).isBefore(now))) {
                stale.add(module.moduleKey());   // 服务失联：路由摘除 + 自检告警
                continue;
            }
            boolean usable = module.serviceUri() != null && !module.serviceUri().isBlank()
                    && module.routes() != null && !module.routes().isEmpty();
            if (!usable) {
                module.routes().forEach(r -> missing.add(r + " (module=" + module.moduleKey() + ", 无服务地址)"));
                continue;
            }
            // 描述符可声明完整 URL（容器/编排注入）或裸端口（本地开发默认）——
            // 裸端口不是合法绝对 URI，须拼本地地址，否则路由构建失败整表失效
            String uri = module.serviceUri().contains("://")
                    ? module.serviceUri() : "http://127.0.0.1:" + module.serviceUri();
            RouteDefinition definition = new RouteDefinition();
            definition.setId("dynamic-" + module.moduleKey());
            definition.setUri(java.net.URI.create(uri));
            definition.setPredicates(List.of(
                    new PredicateDefinition("Path=" + String.join(",", normalized(module.routes())))));
            definitions.add(definition);
        }
        return new Plan(definitions, missing, stale);
    }

    private static List<String> normalized(List<String> routes) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String route : routes) {
            String normalized = route.endsWith("/") && route.length() > 1
                    ? route.substring(0, route.length() - 1) : route;
            if (seen.add(normalized)) {
                result.add(normalized + "/**");
            }
        }
        return result;
    }
}

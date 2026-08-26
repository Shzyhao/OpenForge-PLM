package com.openforge.gateway.modules;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 模块路由健康指示器（A4 设计 3.3）：路由缺失/心跳过期/注册中心不可达 → DEGRADED，
 * details 携带缺失前缀与失联模块清单——冒烟与监控一眼定位（工程备忘 1）。
 */
@Component
@RequiredArgsConstructor
public class ModuleRoutesHealthIndicator implements ReactiveHealthIndicator {

    private final ModuleRouteRefresher refresher;

    @Override
    public Mono<Health> health() {
        return Mono.fromSupplier(this::build);
    }

    private Health build() {
        List<String> missing = refresher.getMissingRoutes();
        List<String> stale = refresher.getStaleModules();
        boolean degraded = !missing.isEmpty() || !stale.isEmpty() || !refresher.isRegistryReachable()
                || !refresher.isRefreshedOnce();
        Health.Builder builder = degraded ? Health.status("DEGRADED") : Health.up();
        return builder.withDetails(Map.of(
                        "routeMissing", missing,
                        "staleModules", stale,
                        "registryReachable", refresher.isRegistryReachable()))
                .build();
    }
}

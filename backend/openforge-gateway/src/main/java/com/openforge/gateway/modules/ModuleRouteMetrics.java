package com.openforge.gateway.modules;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 模块路由可观测性指标（R15 告警规则配套）：把 /actuator/module-routes 的三清单
 * 暴露为 Micrometer Gauge，Prometheus 告警（prometheus-rules.yml OpenForgeModuleBroken）直接消费。
 */
@Component
@RequiredArgsConstructor
public class ModuleRouteMetrics {

    private final ModuleRouteRefresher refresher;
    private final MeterRegistry registry;

    @PostConstruct
    void register() {
        Gauge.builder("openforge_gateway_route_missing", refresher,
                r -> r.getMissingRoutes().size()).description("网关动态路由缺失前缀数").register(registry);
        Gauge.builder("openforge_gateway_stale_modules", refresher,
                r -> r.getStaleModules().size()).description("心跳过期的注册模块数").register(registry);
        Gauge.builder("openforge_gateway_broken_modules", refresher,
                r -> r.getBrokenModules().size()).description("依赖未启用的 BROKEN 模块数").register(registry);
    }
}

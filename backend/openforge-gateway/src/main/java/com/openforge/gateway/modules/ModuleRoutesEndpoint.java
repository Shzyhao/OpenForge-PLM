package com.openforge.gateway.modules;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 手动刷新动态路由（A4 设计 3.3 阶段一）：POST /actuator/module-routes。 */
@Component
@Endpoint(id = "module-routes")
@RequiredArgsConstructor
public class ModuleRoutesEndpoint {

    private final ModuleRouteRefresher refresher;

    @WriteOperation
    public Map<String, Object> refresh() {
        refresher.refresh();
        return Map.of(
                "routeMissing", refresher.getMissingRoutes(),
                "staleModules", refresher.getStaleModules(),
                "brokenModules", refresher.getBrokenModules(),
                "registryReachable", refresher.isRegistryReachable());
    }

    @org.springframework.boot.actuate.endpoint.annotation.ReadOperation
    public Map<String, Object> status() {
        return Map.of(
                "routeMissing", List.copyOf(refresher.getMissingRoutes()),
                "staleModules", refresher.getStaleModules(),
                "brokenModules", refresher.getBrokenModules(),
                "registryReachable", refresher.isRegistryReachable());
    }
}

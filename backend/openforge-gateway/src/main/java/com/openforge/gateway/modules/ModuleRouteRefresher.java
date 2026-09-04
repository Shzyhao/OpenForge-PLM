package com.openforge.gateway.modules;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 模块动态路由刷新器（A4 设计 3.3）：30s 轮询注册中心 → 计算路由计划 → 增删动态路由。
 * 启动自检：声明前缀未生效 → ERROR 逐条列出（route-missing）；心跳过期模块 → 路由摘除 + 告警。
 * 注册中心不可用时保留上一轮路由（降级可用），健康指示器上报 DEGRADED。
 */
@Slf4j
@Component
public class ModuleRouteRefresher implements ApplicationListener<ApplicationReadyEvent> {

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RouteDefinitionWriter routeDefinitionWriter;
    private final ApplicationEventPublisher eventPublisher;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<String> appliedRouteIds = new CopyOnWriteArrayList<>();
    private final List<String> missingRoutes = new CopyOnWriteArrayList<>();
    private final List<String> staleModules = new CopyOnWriteArrayList<>();
    private volatile boolean registryReachable = true;
    private volatile boolean refreshedOnce = false;

    public ModuleRouteRefresher(
            RouteDefinitionWriter routeDefinitionWriter,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            @Value("${openforge.module.auth-base-url:http://localhost:8081}") String authBaseUrl,
            @Value("${openforge.module.internal-token:openforge-internal-dev-token}") String internalToken) {
        this.routeDefinitionWriter = routeDefinitionWriter;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        refresh();
    }

    @Scheduled(fixedDelayString = "${openforge.module.poll-interval:30000}")  // 毫秒；"30s" 非合法值（需 ISO-8601 PT30S）
    public void scheduledRefresh() {
        refresh();
    }

    public synchronized void refresh() {
        List<ModuleRoutePlan.ModuleView> modules;
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/v1/internal/modules")
                    .retrieve()
                    .body(RESPONSE_TYPE);
            modules = parse(response);
            registryReachable = true;
        } catch (Exception e) {
            registryReachable = false;
            log.warn("注册中心不可用，保留上一轮路由: {}", e.getMessage());
            return;
        }

        ModuleRoutePlan.Plan plan = ModuleRoutePlan.compute(modules, LocalDateTime.now());
        for (String routeId : List.copyOf(appliedRouteIds)) {
            routeDefinitionWriter.delete(Mono.just(routeId)).subscribe();
            appliedRouteIds.remove(routeId);
        }
        for (RouteDefinition definition : plan.definitions()) {
            try {
                routeDefinitionWriter.save(Mono.just(definition)).subscribe();
                appliedRouteIds.add(definition.getId());
            } catch (Exception e) {
                log.error("动态路由写入失败: {} — {}", definition.getId(), e.getMessage());
            }
        }

        missingRoutes.clear();
        missingRoutes.addAll(plan.missingRoutes());
        staleModules.clear();
        staleModules.addAll(plan.staleModules());
        refreshedOnce = true;

        // RouteDefinitionWriter.save 仅写定义存储；必须发布 RefreshRoutesEvent 触发
        // CachingRouteLocator 重建路由表，动态路由才会真正生效
        if (!plan.definitions().isEmpty() || !appliedRouteIds.isEmpty()) {
            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
        }

        // 启动自检（工程备忘 1 机制化）：缺失逐条 ERROR，冒烟一眼可见
        for (String missing : missingRoutes) {
            log.error("route-missing: {} — 注册模块的声明前缀未生效", missing);
        }
        for (String stale : staleModules) {
            log.warn("module-stale: {} — 心跳超时，路由已摘除（服务失联或未启动）", stale);
        }
        log.info("模块动态路由已刷新: {} 条路由（来自 {} 个模块）", appliedRouteIds.size(),
                modules.stream().filter(m -> "ENABLED".equals(m.status())).count());
    }

    /** auth 返回的 SysModule 行 → 视图（routes 为 JSON 字符串列）。 */
    @SuppressWarnings("unchecked")
    private List<ModuleRoutePlan.ModuleView> parse(Map<String, Object> response) {
        List<ModuleRoutePlan.ModuleView> views = new ArrayList<>();
        if (response == null || !Integer.valueOf(0).equals(response.get("code"))) {
            return views;
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> rows)) {
            return views;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) {
                continue;
            }
            List<String> routes = new ArrayList<>();
            Object routesJson = m.get("routes");
            if (routesJson instanceof String json && !json.isBlank()) {
                try {
                    routes = objectMapper.readValue(json,
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                } catch (Exception ignored) {
                    // 路由 JSON 损坏按空处理，由自检报缺失
                }
            }
            LocalDateTime heartbeat = null;
            Object heartbeatText = m.get("heartbeatAt");
            if (heartbeatText instanceof String s && !s.isBlank()) {
                try {
                    heartbeat = LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
                } catch (Exception ignored) {
                }
            }
            views.add(new ModuleRoutePlan.ModuleView(
                    String.valueOf(m.get("moduleKey")),
                    String.valueOf(m.get("moduleType")),
                    String.valueOf(m.get("status")),
                    routes,
                    m.get("serviceUri") == null ? null : String.valueOf(m.get("serviceUri")),
                    heartbeat));
        }
        return views;
    }

    public List<String> getMissingRoutes() {
        return List.copyOf(missingRoutes);
    }

    public List<String> getStaleModules() {
        return List.copyOf(staleModules);
    }

    public boolean isRegistryReachable() {
        return registryReachable;
    }

    public boolean isRefreshedOnce() {
        return refreshedOnce;
    }
}

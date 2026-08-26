package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.auth.entity.SysModule;
import com.openforge.auth.mapper.ModuleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模块注册中心（A4 设计 3.2/4）：sys_module 表的自注册 upsert、心跳、启停与查询。
 * 安全校验：moduleKey/路由白名单；路由不得劫持 KERNEL 前缀；同一路由前缀不得双主人。
 */
@Service
@RequiredArgsConstructor
public class ModuleRegistryService {

    /** KERNEL 模块前缀（auth 自身）——其他模块的路由不得覆盖（A4 设计 4 安全红线）。 */
    private static final Set<String> KERNEL_PREFIXES = Set.of(
            "/api/v1/auth", "/api/v1/users", "/api/v1/roles", "/api/v1/permissions",
            "/api/v1/orgs", "/api/v1/numbers", "/api/v1/security", "/api/v1/internal",
            "/api/v1/modules");

    private static final Pattern MODULE_KEY = Pattern.compile("^[a-z][a-z0-9_:-]{2,63}$");
    private static final Pattern ROUTE = Pattern.compile("^/api/v1/[a-z0-9_/-]+/?$");
    private static final Set<String> TYPES = Set.of("KERNEL", "BUSINESS", "AI", "EXTENSION");

    private final ModuleMapper moduleMapper;
    private final ObjectMapper objectMapper;
    private final SecurityLogService securityLogService;

    /** 自注册 upsert（内部接口，发布者已过令牌校验）：已存在则刷新定义与心跳，status 尊重管理端。 */
    public SysModule register(String moduleKey, String moduleType, String displayName, String version,
                              List<String> routes, List<?> menu, List<String> dependencies,
                              String flywayTable, String healthPath) {
        validate(moduleKey, moduleType, routes);
        checkRouteOwnership(moduleKey, routes);

        SysModule existing = moduleMapper.selectOne(
                new LambdaQueryWrapper<SysModule>().eq(SysModule::getModuleKey, moduleKey));
        boolean created = existing == null;
        SysModule module = existing != null ? existing : new SysModule();
        module.setModuleKey(moduleKey);
        module.setModuleType(moduleType);
        module.setDisplayName(displayName == null || displayName.isBlank() ? moduleKey : displayName);
        module.setVersion(version);
        module.setRoutes(toJson(routes));
        module.setMenu(menu == null ? null : toJson(menu));
        module.setDependencies(toJson(dependencies == null ? List.of() : dependencies));
        module.setFlywayTable(flywayTable == null || flywayTable.isBlank() ? null : flywayTable);
        module.setHealthPath(healthPath == null || healthPath.isBlank() ? null : healthPath);
        module.setHeartbeatAt(java.time.LocalDateTime.now());
        if (created) {
            module.setStatus("ENABLED");
            moduleMapper.insert(module);
        } else {
            moduleMapper.updateById(module);
        }
        securityLogService.audit(null, created ? "MODULE_REGISTER" : "MODULE_HEARTBEAT",
                "MODULE", moduleKey, version);
        return module;
    }

    private void validate(String moduleKey, String moduleType, List<String> routes) {
        if (moduleKey == null || !MODULE_KEY.matcher(moduleKey).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "moduleKey 非法: " + moduleKey);
        }
        if (!TYPES.contains(moduleType)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "moduleType 须为 KERNEL/BUSINESS/AI/EXTENSION");
        }
        if (routes == null || routes.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "至少声明一个路由前缀");
        }
        for (String route : routes) {
            if (!ROUTE.matcher(route).matches()) {
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "路由前缀非法: " + route);
            }
            for (String kernel : KERNEL_PREFIXES) {
                if (route.equals(kernel) || route.startsWith(kernel + "/")) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT,
                            "路由不得覆盖内核前缀: " + route + "（KERNEL: " + kernel + "）");
                }
            }
        }
    }

    /** 同一路由前缀不允许两个主人（防误注册抢占他人 API 面）。 */
    private void checkRouteOwnership(String moduleKey, List<String> routes) {
        Set<String> claimed = new HashSet<>();
        for (String route : routes) {
            claimed.add(normalize(route));
        }
        for (SysModule m : moduleMapper.selectList(null)) {
            if (m.getModuleKey().equals(moduleKey)) {
                continue;
            }
            for (String route : fromJsonList(m.getRoutes())) {
                if (claimed.contains(normalize(route))) {
                    throw new BizException(ErrorCode.MODULE_ALREADY_EXISTS,
                            "路由前缀已被注册: " + route + "（属于模块 " + m.getModuleKey() + "）");
                }
            }
        }
    }

    private static String normalize(String route) {
        return route.endsWith("/") ? route.substring(0, route.length() - 1) : route;
    }

    /** 前端可见的启用模块（菜单贡献来源，A4 设计 3.5）。 */
    public List<SysModule> listEnabled() {
        return moduleMapper.selectList(
                new LambdaQueryWrapper<SysModule>().eq(SysModule::getStatus, "ENABLED")
                        .orderByAsc(SysModule::getId));
    }

    public List<SysModule> listAll() {
        return moduleMapper.selectList(
                new LambdaQueryWrapper<SysModule>().orderByAsc(SysModule::getId));
    }

    public List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? new ArrayList<>() : value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模块定义序列化失败");
        }
    }
}

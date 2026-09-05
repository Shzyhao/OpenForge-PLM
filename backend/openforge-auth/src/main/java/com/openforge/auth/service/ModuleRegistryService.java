package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.auth.entity.SysModule;
import com.openforge.auth.mapper.ModuleMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模块注册中心（A4 设计 3.2/4）：sys_module 表的自注册 upsert、心跳、启停与查询。
 * 安全校验：moduleKey/路由白名单；路由不得劫持 KERNEL 前缀；同一路由前缀不得双主人。
 */
@Slf4j
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
                              String flywayTable, String healthPath, String serviceUri, Long ownerRef) {
        validate(moduleKey, moduleType, routes);
        if ("EXTENSION".equals(moduleType) && ownerRef == null) {
            // A4 设计 4：EXTENSION 仅允许发布流水线注册（owner_ref 必须指向已发布元对象）
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "EXTENSION 模块必须携带 ownerRef");
        }
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
        module.setServiceUri(serviceUri == null || serviceUri.isBlank() ? null : serviceUri);
        module.setOwnerRef(ownerRef);
        module.setHeartbeatAt(java.time.LocalDateTime.now());
        if (created) {
            module.setStatus("ENABLED");
            moduleMapper.insert(module);
        } else {
            moduleMapper.updateById(module);
        }
        securityLogService.audit(null, created ? "MODULE_REGISTER" : "MODULE_HEARTBEAT",
                "MODULE", moduleKey, version);
        evaluateDependencies();
        return module;
    }

    /** 停用即摘除（A4 设计 3.3/3.4）：KERNEL 不可停（4021）；存在启用中依赖方拒绝（4020）。 */
    public void disable(String moduleKey) {
        SysModule module = requireModule(moduleKey);
        if ("KERNEL".equals(module.getModuleType())) {
            throw new BizException(ErrorCode.MODULE_KERNEL_IMMUTABLE);
        }
        List<String> dependents = enabledDependentsOf(moduleKey);
        if (!dependents.isEmpty()) {
            throw new BizException(ErrorCode.MODULE_HAS_DEPENDENTS,
                    "存在启用中的依赖方，先停用它们: " + String.join(", ", dependents));
        }
        module.setStatus("DISABLED");
        moduleMapper.updateById(module);
        securityLogService.audit(null, "MODULE_DISABLE", "MODULE", moduleKey, null);
        evaluateDependencies();
    }

    public void enable(String moduleKey) {
        SysModule module = requireModule(moduleKey);
        module.setStatus("ENABLED");
        moduleMapper.updateById(module);
        securityLogService.audit(null, "MODULE_ENABLE", "MODULE", moduleKey, null);
        evaluateDependencies();
    }

    /**
     * 依赖守护（A4 设计 3.4）：非 DISABLED 模块若依赖缺失/未启用的模块 → BROKEN（路由摘除）；
     * 依赖恢复后自动回归 ENABLED。DISABLED 为管理端决策，不参与自动恢复。定点求值至稳定。
     */
    void evaluateDependencies() {
        List<SysModule> all = moduleMapper.selectList(null);
        Map<String, String> statusByKey = new java.util.HashMap<>();
        for (SysModule m : all) {
            statusByKey.put(m.getModuleKey(), m.getStatus());
        }
        for (int round = 0; round <= all.size(); round++) {
            boolean changed = false;
            for (SysModule m : all) {
                if ("DISABLED".equals(statusByKey.get(m.getModuleKey()))) {
                    continue;
                }
                boolean satisfied = fromJsonList(m.getDependencies()).stream()
                        .allMatch(dep -> "ENABLED".equals(statusByKey.get(dep)));
                String target = satisfied ? "ENABLED" : "BROKEN";
                if (!target.equals(statusByKey.get(m.getModuleKey()))) {
                    statusByKey.put(m.getModuleKey(), target);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        for (SysModule m : all) {
            String target = statusByKey.get(m.getModuleKey());
            if (!m.getStatus().equals(target)) {
                // F2 设计 3.4 承诺的守护日志：BROKEN 摘除必须留痕，依赖恢复留信息级轨迹
                if ("BROKEN".equals(target)) {
                    List<String> unsatisfied = fromJsonList(m.getDependencies()).stream()
                            .filter(dep -> !"ENABLED".equals(statusByKey.get(dep)))
                            .toList();
                    log.error("module broken: {} — missing dependency: {}", m.getModuleKey(),
                            String.join(", ", unsatisfied));
                } else if ("ENABLED".equals(target)) {
                    log.info("module recovered: {} — 依赖恢复，自动回归 ENABLED", m.getModuleKey());
                }
                // 定点 UPDATE 只改 status：整行回写会用求值前的陈旧快照覆盖并发心跳刚写的
                // heartbeat_at/serviceUri（评审实锤，最多一个周期误摘路由）
                moduleMapper.update(null, new LambdaUpdateWrapper<SysModule>()
                        .eq(SysModule::getId, m.getId())
                        .set(SysModule::getStatus, target));
            }
        }
    }

    /** 反查：以 moduleKey 为依赖且当前未停用的模块。 */
    private List<String> enabledDependentsOf(String moduleKey) {
        List<String> dependents = new ArrayList<>();
        for (SysModule m : moduleMapper.selectList(null)) {
            if (m.getModuleKey().equals(moduleKey) || "DISABLED".equals(m.getStatus())) {
                continue;
            }
            if (fromJsonList(m.getDependencies()).contains(moduleKey)) {
                dependents.add(m.getModuleKey());
            }
        }
        return dependents;
    }

    private SysModule requireModule(String moduleKey) {
        SysModule module = moduleMapper.selectOne(
                new LambdaQueryWrapper<SysModule>().eq(SysModule::getModuleKey, moduleKey));
        if (module == null) {
            throw new BizException(ErrorCode.MODULE_NOT_FOUND);
        }
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
            // KERNEL 自身声明内核前缀合法（auth 自注册依赖此路径）；豁免仅限 KERNEL 类型，
            // 防劫持目标仍是 BUSINESS/AI/EXTENSION 冒充内核 API 面
            if ("KERNEL".equals(moduleType)) {
                continue;
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

    public SysModule findByKey(String moduleKey) {
        return moduleMapper.selectOne(
                new LambdaQueryWrapper<SysModule>().eq(SysModule::getModuleKey, moduleKey));
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

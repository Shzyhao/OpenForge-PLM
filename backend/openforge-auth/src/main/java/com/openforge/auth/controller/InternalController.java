package com.openforge.auth.controller;

import com.openforge.auth.dto.InternalPermissionView;
import com.openforge.auth.service.NumberRuleService;
import com.openforge.auth.service.PermissionService;
import com.openforge.auth.service.RbacService;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口：仅限内网直连调用（不经网关路由，外部访问默认 404），
 * 并强制校验共享内网令牌 X-Internal-Token（部署层网络隔离 + 令牌双重防护）。
 */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalController {

    private final NumberRuleService numberRuleService;
    private final RbacService rbacService;
    private final PermissionService permissionService;
    private final com.openforge.auth.service.ModuleRegistryService moduleRegistryService;
    private final com.openforge.auth.mapper.UserMapper userMapper;
    private final String internalToken;

    public InternalController(NumberRuleService numberRuleService,
                              RbacService rbacService,
                              PermissionService permissionService,
                              com.openforge.auth.service.ModuleRegistryService moduleRegistryService,
                              com.openforge.auth.mapper.UserMapper userMapper,
                              @Value("${openforge.internal.token:openforge-internal-dev-token}") String internalToken) {
        this.numberRuleService = numberRuleService;
        this.rbacService = rbacService;
        this.permissionService = permissionService;
        this.moduleRegistryService = moduleRegistryService;
        this.userMapper = userMapper;
        this.internalToken = internalToken;
    }

    @PostMapping("/numbers/next/{ruleKey}")
    public ApiResponse<String> nextNumber(@PathVariable String ruleKey,
                                          @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        return ApiResponse.ok(numberRuleService.nextNumber(ruleKey));
    }

    @GetMapping("/permissions/{userId}")
    public ApiResponse<InternalPermissionView> permissions(
            @PathVariable Long userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        com.openforge.auth.entity.SysUser user = userMapper.selectById(userId);
        String userType = user == null ? "NORMAL" : user.getUserType();
        return ApiResponse.ok(new InternalPermissionView(
                userId,
                userType,
                rbacService.getRoleCodesOfUser(userId),
                permissionService.getPermissionCodesOfUser(userId)));
    }

    /**
     * 幂等创建权限点（F2 发布流水线：发布时自动创建 {objectKey}:view/create/update/delete 四点，
     * 可选绑定角色——如 ADMIN）。重复调用复用既有权限点，不报冲突。
     */
    @PostMapping("/permissions")
    public ApiResponse<java.util.Map<String, Object>> ensurePermission(
            @RequestBody EnsurePermissionRequest request,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        boolean created = permissionService.ensurePermission(
                request.getPermCode(), request.getPermName(), request.getBindRoleCodes());
        return ApiResponse.ok(java.util.Map.of("created", created));
    }

    @lombok.Data
    public static class EnsurePermissionRequest {
        @jakarta.validation.constraints.NotBlank
        private String permCode;
        @jakarta.validation.constraints.NotBlank
        private String permName;
        private java.util.List<String> bindRoleCodes;
    }

    /**
     * 模块自注册/心跳（A4 设计 3.2）：幂等 upsert，路由白名单与 KERNEL 前缀防劫持在服务层校验。
     */
    @PostMapping("/modules")
    public ApiResponse<java.util.Map<String, Object>> registerModule(
            @RequestBody ModuleRegisterRequest request,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        com.openforge.auth.entity.SysModule module = moduleRegistryService.register(
                request.getModuleKey(), request.getModuleType(), request.getDisplayName(),
                request.getVersion(), request.getRoutes(), request.getMenu(),
                request.getDependencies(), request.getFlywayTable(), request.getHealthPath(),
                request.getServiceUri());
        return ApiResponse.ok(java.util.Map.of("moduleKey", module.getModuleKey(),
                "status", module.getStatus()));
    }

    /** 网关动态路由数据源（A4 设计 3.3）：全量模块的状态/路由/服务地址/心跳。 */
    @GetMapping("/modules")
    public ApiResponse<java.util.List<com.openforge.auth.entity.SysModule>> listModules(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        return ApiResponse.ok(moduleRegistryService.listAll());
    }

    /** 单模块状态（A4 设计 3.4 ensureAvailable 数据源：调用方前置检查依赖模块可用性）。 */
    @GetMapping("/modules/status/{moduleKey}")
    public ApiResponse<java.util.Map<String, String>> moduleStatus(
            @PathVariable String moduleKey,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        com.openforge.auth.entity.SysModule module = moduleRegistryService.findByKey(moduleKey);
        return ApiResponse.ok(module == null ? java.util.Map.of("moduleKey", moduleKey, "status", "NOT_FOUND")
                : java.util.Map.of("moduleKey", module.getModuleKey(), "status", module.getStatus()));
    }

    @lombok.Data
    public static class ModuleRegisterRequest {
        @jakarta.validation.constraints.NotBlank
        private String moduleKey;
        @jakarta.validation.constraints.NotBlank
        private String moduleType;
        private String displayName;
        private String version;
        private java.util.List<String> routes;
        private java.util.List<java.util.Map<String, String>> menu;
        private java.util.List<String> dependencies;
        private String flywayTable;
        private String healthPath;
        private String serviceUri;
    }

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }
}

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
    private final com.openforge.auth.mapper.UserMapper userMapper;
    private final String internalToken;

    public InternalController(NumberRuleService numberRuleService,
                              RbacService rbacService,
                              PermissionService permissionService,
                              com.openforge.auth.mapper.UserMapper userMapper,
                              @Value("${openforge.internal.token:openforge-internal-dev-token}") String internalToken) {
        this.numberRuleService = numberRuleService;
        this.rbacService = rbacService;
        this.permissionService = permissionService;
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

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }
}

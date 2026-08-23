package com.openforge.auth.controller;

import com.openforge.auth.dto.UserInfoResponse;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.service.OrgService;
import com.openforge.auth.service.RbacService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户信息接口。用户身份来自网关注入的信任头 X-User-Id（见 gateway AuthGlobalFilter），
 * 业务服务只信任网关转发链路内的该头，不直接接受外部传入。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    /** 与网关 AuthGlobalFilter 的信任头约定保持一致 */
    static final String HEADER_USER_ID = "X-User-Id";

    private final RbacService rbacService;
    private final OrgService orgService;

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> me(HttpServletRequest request) {
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少网关信任头，请经由网关访问");
        }
        Long userId;
        try {
            userId = Long.valueOf(userIdHeader);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "信任头格式非法");
        }

        SysUser user = rbacService.findUser(userId);
        if (user == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return ApiResponse.ok(new UserInfoResponse(
                user.getId(), user.getUsername(), user.getDisplayName(),
                rbacService.getRoleCodesOfUser(userId)));
    }

    /** 将用户挂接到组织（org:manage 权限）。body: {"orgId": 123}，orgId 为 null 表示移出组织。 */
    @PutMapping("/{id}/org")
    @RequirePermission("org:manage")
    public ApiResponse<Void> assignOrg(@PathVariable("id") Long userId,
                                       @RequestBody(required = false) Map<String, Long> body) {
        Long orgId = body == null ? null : body.get("orgId");
        orgService.assignUserOrg(userId, orgId);
        return ApiResponse.ok();
    }
}

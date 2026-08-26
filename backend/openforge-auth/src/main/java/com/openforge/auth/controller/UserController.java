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
    private final com.openforge.auth.service.UserAdminService userAdminService;
    private final com.openforge.auth.service.PermissionService permissionService;

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
                rbacService.getRoleCodesOfUser(userId),
                permissionService.menuCodesOfUser(user),
                permissionService.getPermissionCodesOfUser(userId),
                user.getUserType()));
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

    // ===== 管理员用户管理（方案 D 组，user:manage + admin 保护矩阵在服务层） =====

    @org.springframework.web.bind.annotation.PostMapping("")
    @RequirePermission("user:manage")
    public ApiResponse<SysUser> createUser(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.openforge.auth.dto.CreateUserRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(userAdminService.create(request, currentUserId(httpRequest)));
    }

    @org.springframework.web.bind.annotation.GetMapping("")
    public ApiResponse<com.openforge.auth.dto.PageResponse<SysUser>> page(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "1") long page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") long pageSize,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String username,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long roleId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        return ApiResponse.ok(userAdminService.page(page, pageSize, username, roleId, status));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @RequirePermission("user:manage")
    public ApiResponse<SysUser> update(@PathVariable Long id,
                                       @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
                                       HttpServletRequest httpRequest) {
        return ApiResponse.ok(userAdminService.update(id, currentUserId(httpRequest),
                body.get("displayName"), body.get("email"), null));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/enable")
    @RequirePermission("user:manage")
    public ApiResponse<SysUser> enable(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(userAdminService.changeStatus(id, currentUserId(httpRequest), true));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/disable")
    @RequirePermission("user:manage")
    public ApiResponse<SysUser> disable(@PathVariable Long id, HttpServletRequest httpRequest) {
        return ApiResponse.ok(userAdminService.changeStatus(id, currentUserId(httpRequest), false));
    }

    @org.springframework.web.bind.annotation.PostMapping("/{id}/reset-password")
    @RequirePermission("user:manage")
    public ApiResponse<Void> resetPassword(@PathVariable Long id,
                                           @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
                                           HttpServletRequest httpRequest) {
        // 一次性返回新密码（前端弹窗展示，不落库明文）
        userAdminService.resetPassword(id, currentUserId(httpRequest), body.get("password"));
        return ApiResponse.ok();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @RequirePermission("user:manage")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest httpRequest) {
        userAdminService.delete(id, currentUserId(httpRequest));
        return ApiResponse.ok();
    }

    /** 批量启停（方案 D9）。body: {"ids":[1,2],"enable":true} */
    @org.springframework.web.bind.annotation.PostMapping("/batch-status")
    @RequirePermission("user:manage")
    public ApiResponse<Void> batchStatus(
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body,
            HttpServletRequest httpRequest) {
        @SuppressWarnings("unchecked")
        java.util.List<Number> rawIds = (java.util.List<Number>) body.getOrDefault("ids", java.util.List.of());
        java.util.List<Long> ids = rawIds.stream().map(Number::longValue).toList();
        boolean enable = Boolean.TRUE.equals(body.get("enable"));
        userAdminService.changeStatusBatch(ids, currentUserId(httpRequest), enable);
        return ApiResponse.ok();
    }

    /** 修改自己的密码（方案 E5，无需权限点——本人凭证）。 */
    @PutMapping("/me/password")
    public ApiResponse<Void> changeMyPassword(
            @org.springframework.web.bind.annotation.RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        userAdminService.changeMyPassword(currentUserId(httpRequest),
                body.get("oldPassword"), body.get("newPassword"));
        return ApiResponse.ok();
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_USER_ID);
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.openforge.auth.controller;

import com.openforge.auth.dto.AssignRolesRequest;
import com.openforge.auth.dto.CreateRoleRequest;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.service.PermissionService;
import com.openforge.auth.service.RbacService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 角色管理接口。
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RbacService rbacService;
    private final PermissionService permissionService;

    @GetMapping
    public ApiResponse<List<SysRole>> list() {
        return ApiResponse.ok(rbacService.listRoles());
    }

    @PostMapping
    @RequirePermission("role:create")
    public ApiResponse<SysRole> create(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(rbacService.createRole(request.getRoleCode(), request.getRoleName()));
    }

    /** 编辑角色（B3）：名称/描述。 */
    @PutMapping("/{id}")
    @RequirePermission("role:create")
    public ApiResponse<SysRole> update(@PathVariable Long id,
                                       @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(rbacService.updateRole(id, request.getRoleName(), request.getDescription()));
    }

    /** 删除角色（B4）：内置/有成员拒绝。 */
    @DeleteMapping("/{id}")
    @RequirePermission("role:create")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        rbacService.deleteRole(id);
        return ApiResponse.ok();
    }

    // ===== 成员管理（B5） =====

    @GetMapping("/{id}/members")
    public ApiResponse<List<com.openforge.auth.entity.SysUser>> members(@PathVariable Long id) {
        return ApiResponse.ok(rbacService.members(id));
    }

    @PostMapping("/{id}/members")
    @RequirePermission("role:assign")
    public ApiResponse<Void> addMembers(@PathVariable Long id,
                                        @RequestBody Map<String, List<Long>> body) {
        rbacService.addMembers(id, body.getOrDefault("userIds", List.of()));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    @RequirePermission("role:assign")
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        rbacService.removeMember(id, userId);
        return ApiResponse.ok();
    }

    // ===== 角色权限矩阵（B6/C 组） =====

    @GetMapping("/{id}/permissions")
    public ApiResponse<List<String>> rolePermissions(@PathVariable Long id) {
        return ApiResponse.ok(permissionService.getPermissionCodesOfRole(id));
    }

    /** 角色权限矩阵保存（覆盖式）。 */
    @PutMapping("/{id}/permissions")
    @RequirePermission("perm:manage")
    public ApiResponse<Void> bindPermissions(@PathVariable Long id,
                                             @RequestBody Map<String, List<Long>> body) {
        permissionService.bindRolePermissions(id, body.getOrDefault("permissionIds", List.of()));
        return ApiResponse.ok();
    }

    @lombok.Data
    public static class UpdateRoleRequest {
        private String roleName;
        private String description;
    }

    @PutMapping("/users/{userId}")
    @RequirePermission("role:assign")
    public ApiResponse<Void> assign(@PathVariable Long userId,
                                    @Valid @RequestBody AssignRolesRequest request) {
        rbacService.assignRoles(userId, request.getRoleIds());
        return ApiResponse.ok();
    }
}

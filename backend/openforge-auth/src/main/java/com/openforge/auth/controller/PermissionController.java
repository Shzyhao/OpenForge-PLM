package com.openforge.auth.controller;

import com.openforge.auth.dto.CreateRoleRequest;
import com.openforge.auth.entity.SysPermission;
import com.openforge.auth.service.PermissionService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    @GetMapping("/tree")
    public ApiResponse<java.util.Map<String, Object>> tree() {
        return ApiResponse.ok(permissionService.permissionTree());
    }

    @GetMapping
    public ApiResponse<List<SysPermission>> list() {
        return ApiResponse.ok(permissionService.listPermissions());
    }

    @PostMapping
    @RequirePermission("perm:manage")
    public ApiResponse<SysPermission> create(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(permissionService.createPermission(request.getRoleCode(), request.getRoleName()));
    }

    @PutMapping("/roles/{roleId}")
    @RequirePermission("perm:manage")
    public ApiResponse<Void> bind(@PathVariable Long roleId,
                                  @RequestBody Map<String, List<Long>> body) {
        permissionService.bindRolePermissions(roleId, body.get("permissionIds"));
        return ApiResponse.ok();
    }

    @GetMapping("/roles/{roleId}")
    public ApiResponse<List<String>> rolePermissions(@PathVariable Long roleId) {
        return ApiResponse.ok(permissionService.getPermissionCodesOfRole(roleId));
    }
}

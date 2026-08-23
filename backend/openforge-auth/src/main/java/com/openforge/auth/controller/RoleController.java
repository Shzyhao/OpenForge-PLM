package com.openforge.auth.controller;

import com.openforge.auth.dto.AssignRolesRequest;
import com.openforge.auth.dto.CreateRoleRequest;
import com.openforge.auth.entity.SysRole;
import com.openforge.auth.service.RbacService;
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

/**
 * 角色管理接口。
 * TODO(M1): 权限点校验拦截器上线后，本接口要求 role:assign / role:create 权限点（开发文档 10.2）。
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RbacService rbacService;

    @GetMapping
    public ApiResponse<List<SysRole>> list() {
        return ApiResponse.ok(rbacService.listRoles());
    }

    @PostMapping
    public ApiResponse<SysRole> create(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(rbacService.createRole(request.getRoleCode(), request.getRoleName()));
    }

    @PutMapping("/users/{userId}")
    public ApiResponse<Void> assign(@PathVariable Long userId,
                                    @Valid @RequestBody AssignRolesRequest request) {
        rbacService.assignRoles(userId, request.getRoleIds());
        return ApiResponse.ok();
    }
}

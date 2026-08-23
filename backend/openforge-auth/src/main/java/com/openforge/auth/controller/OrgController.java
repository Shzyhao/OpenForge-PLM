package com.openforge.auth.controller;

import com.openforge.auth.dto.CreateOrgRequest;
import com.openforge.auth.dto.OrgNodeResponse;
import com.openforge.auth.dto.UpdateOrgRequest;
import com.openforge.auth.dto.UserBriefResponse;
import com.openforge.auth.entity.SysOrg;
import com.openforge.auth.service.OrgService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    @PostMapping
    @RequirePermission("org:manage")
    public ApiResponse<SysOrg> create(@Valid @RequestBody CreateOrgRequest request) {
        return ApiResponse.ok(orgService.createOrg(request.getOrgCode(), request.getOrgName(),
                request.getParentId(), request.getSortOrder()));
    }

    @GetMapping("/tree")
    public ApiResponse<List<OrgNodeResponse>> tree() {
        return ApiResponse.ok(orgService.fullTree());
    }

    @PutMapping("/{id}")
    @RequirePermission("org:manage")
    public ApiResponse<SysOrg> update(@PathVariable Long id,
                                      @RequestBody UpdateOrgRequest request) {
        return ApiResponse.ok(orgService.updateOrg(id, request.getOrgName(), request.getSortOrder()));
    }

    @PutMapping("/{id}/parent")
    @RequirePermission("org:manage")
    public ApiResponse<Void> move(@PathVariable Long id, @RequestBody Map<String, Long> body) {
        orgService.moveOrg(id, body.get("parentId"));
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    @RequirePermission("org:manage")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        orgService.deleteOrg(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/users")
    public ApiResponse<List<UserBriefResponse>> users(
            @PathVariable Long id,
            @RequestParam(name = "includeChildren", defaultValue = "false") boolean includeChildren) {
        return ApiResponse.ok(orgService.listUsers(id, includeChildren));
    }
}

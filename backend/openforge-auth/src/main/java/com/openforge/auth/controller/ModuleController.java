package com.openforge.auth.controller;

import com.openforge.auth.entity.SysModule;
import com.openforge.auth.service.ModuleRegistryService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块查询 API（A4 设计 3.5）：/modules 面向前端（启用模块 + 菜单贡献），
 * /modules/admin 面向管理端（module:manage，全量含状态/版本/心跳/迁移表）。
 */
@RestController
@RequestMapping("/api/v1/modules")
@RequiredArgsConstructor
public class ModuleController {

    private final ModuleRegistryService moduleRegistryService;

    /** 启用模块的菜单贡献（登录即可见；菜单渲染与动态路由随 A4-2/A4-3 接入）。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> enabledModules() {
        List<Map<String, Object>> result = moduleRegistryService.listEnabled().stream()
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("moduleKey", m.getModuleKey());
                    item.put("moduleType", m.getModuleType());
                    item.put("displayName", m.getDisplayName());
                    item.put("version", m.getVersion());
                    item.put("menu", m.getMenu());
                    return item;
                })
                .toList();
        return ApiResponse.ok(result);
    }

    @GetMapping("/admin")
    @RequirePermission("module:manage")
    public ApiResponse<List<SysModule>> adminList() {
        return ApiResponse.ok(moduleRegistryService.listAll());
    }
}

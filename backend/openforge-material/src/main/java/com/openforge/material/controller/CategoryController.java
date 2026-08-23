package com.openforge.material.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.material.dto.CreateCategoryRequest;
import com.openforge.material.dto.CategoryNodeResponse;
import com.openforge.material.entity.PartCategory;
import com.openforge.material.service.CategoryService;
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

@RestController
@RequestMapping("/api/v1/part-categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @PostMapping
    @RequirePermission("category:manage")
    public ApiResponse<PartCategory> create(@Valid @RequestBody CreateCategoryRequest request) {
        return ApiResponse.ok(categoryService.create(request.getCategoryCode(),
                request.getCategoryName(), request.getParentId(), request.getSortOrder()));
    }

    @GetMapping("/tree")
    public ApiResponse<List<CategoryNodeResponse>> tree() {
        return ApiResponse.ok(categoryService.tree());
    }

    /** 设置分类属性模板（JSON 数组：key/label/type/required），物料创建与编辑时据此校验。 */
    @PutMapping("/{id}/attr-template")
    @RequirePermission("category:manage")
    public ApiResponse<Void> setAttrTemplate(@PathVariable Long id, @RequestBody String templateJson) {
        categoryService.setAttrTemplate(id, templateJson);
        return ApiResponse.ok();
    }
}

package com.openforge.material.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.material.dto.CreatePartRequest;
import com.openforge.material.dto.PageResponse;
import com.openforge.material.dto.UpdatePartRequest;
import com.openforge.material.entity.Part;
import com.openforge.material.service.PartService;
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

@RestController
@RequestMapping("/api/v1/parts")
@RequiredArgsConstructor
public class PartController {

    private final PartService partService;

    @PostMapping
    @RequirePermission("part:create")
    public ApiResponse<Part> create(@Valid @RequestBody CreatePartRequest request) {
        return ApiResponse.ok(partService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResponse<Part>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String lifecycleState) {
        return ApiResponse.ok(partService.page(page, pageSize, categoryId, name, type, lifecycleState));
    }

    @GetMapping("/{id}")
    public ApiResponse<Part> detail(@PathVariable Long id) {
        return ApiResponse.ok(partService.detail(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("part:update")
    public ApiResponse<Part> update(@PathVariable Long id, @Valid @RequestBody UpdatePartRequest request) {
        return ApiResponse.ok(partService.updateDraft(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("part:delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        partService.deleteDraft(id);
        return ApiResponse.ok();
    }
}

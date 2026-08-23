package com.openforge.change.controller;

import com.openforge.change.dto.EcrDetailResponse;
import com.openforge.change.dto.EcrRequest;
import com.openforge.change.dto.PageResponse;
import com.openforge.change.entity.ChangeRequest;
import com.openforge.change.service.EcrService;
import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/changes")
@RequiredArgsConstructor
public class ChangeController {

    private final EcrService ecrService;

    @PostMapping("/requests")
    @RequirePermission("change:manage")
    public ApiResponse<ChangeRequest> create(@Valid @RequestBody EcrRequest request,
                                             HttpServletRequest httpRequest) {
        return ApiResponse.ok(ecrService.create(request, currentUserId(httpRequest)));
    }

    @GetMapping("/requests")
    public ApiResponse<PageResponse<ChangeRequest>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String title) {
        return ApiResponse.ok(ecrService.page(page, pageSize, title));
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<EcrDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(ecrService.detail(id));
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
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

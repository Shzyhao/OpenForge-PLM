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

    // ===== 回收站（v1.25 设计 §4）=====

    @org.springframework.web.bind.annotation.DeleteMapping("/requests/{id}")
    @com.openforge.common.annotation.RequirePermission("change:manage")
    public ApiResponse<Void> deleteRequest(@org.springframework.web.bind.annotation.PathVariable Long id) {
        ecrService.delete(id);
        return ApiResponse.ok();
    }

    @org.springframework.web.bind.annotation.GetMapping("/requests/recycle")
    public ApiResponse<java.util.List<com.openforge.change.entity.ChangeRequest>> requestRecycle() {
        return ApiResponse.ok(ecrService.trashed());
    }

    @org.springframework.web.bind.annotation.PostMapping("/requests/{id}/restore")
    @com.openforge.common.annotation.RequirePermission("change:manage")
    public ApiResponse<com.openforge.change.entity.ChangeRequest> restoreRequest(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return ApiResponse.ok(ecrService.restore(id));
    }

    /** 彻底删除（不可逆）。 */
    @org.springframework.web.bind.annotation.PostMapping("/requests/{id}/purge")
    @com.openforge.common.annotation.RequirePermission("change:manage")
    public ApiResponse<com.openforge.change.entity.ChangeRequest> purgeRequest(@org.springframework.web.bind.annotation.PathVariable Long id) {
        return ApiResponse.ok(ecrService.purge(id));
    }

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
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String changeType) {
        return ApiResponse.ok(ecrService.page(page, pageSize, title, changeType));
    }

    /** FAILED/PENDING 变更单人工重试执行（决策 D6：审批与执行分离）。 */
    @PostMapping("/requests/{id}/apply")
    @RequirePermission("change:manage")
    public ApiResponse<ChangeRequest> retryApply(@PathVariable Long id) {
        return ApiResponse.ok(ecrService.retryApply(id));
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<EcrDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(ecrService.detail(id));
    }

    /** ECR 统计（报表）：by=state（默认）/ type。 */
    @GetMapping("/stats")
    public ApiResponse<java.util.Map<String, Long>> stats(@RequestParam(defaultValue = "state") String by) {
        return ApiResponse.ok(ecrService.stats(by));
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

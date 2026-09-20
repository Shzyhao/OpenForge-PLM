package com.openforge.workflow.controller;

import com.openforge.common.api.ApiResponse;
import com.openforge.workflow.entity.WorkflowDelegate;
import com.openforge.workflow.service.DelegateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审批委托规则（v1.23 设计 §2.4）：委托属个人操作，登录即可（不走 @RequirePermission），
 * 越权由归属校验兜底——principal 服务端强制为当前用户，撤销仅限本人规则。
 */
@RestController
@RequestMapping("/api/v1/workflow/delegates")
@RequiredArgsConstructor
public class DelegateController {

    private final DelegateService delegateService;

    @PostMapping
    public ApiResponse<WorkflowDelegate> create(@jakarta.validation.Valid @RequestBody CreateRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.ok(delegateService.create(currentUserId(request), body.getAgentId(),
                body.getDefKey(), body.getStartTime(), body.getEndTime(), body.getRemark()));
    }

    @GetMapping
    public ApiResponse<List<WorkflowDelegate>> mine(HttpServletRequest request) {
        return ApiResponse.ok(delegateService.mine(currentUserId(request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        delegateService.delete(id, currentUserId(request));
        return ApiResponse.ok();
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

    @Data
    public static class CreateRequest {
        @NotNull
        private Long agentId;
        /** NULL=全部流程 */
        private String defKey;
        @NotNull
        private LocalDateTime startTime;
        /** NULL=长期有效 */
        private LocalDateTime endTime;
        private String remark;
    }
}

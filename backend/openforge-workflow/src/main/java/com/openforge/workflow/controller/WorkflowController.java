package com.openforge.workflow.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.workflow.entity.WorkflowDef;
import com.openforge.workflow.entity.WorkflowInstance;
import com.openforge.workflow.entity.WorkflowTask;
import com.openforge.workflow.service.WorkflowEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowEngine engine;

    @PostMapping("/defs")
    @RequirePermission("workflow:manage")
    public ApiResponse<WorkflowDef> deploy(@RequestBody DeployRequest request,
                                           HttpServletRequest httpRequest) {
        return ApiResponse.ok(engine.deploy(request.getDefKey(), request.getName(),
                request.getDefinition(), currentUserId(httpRequest)));
    }

    @GetMapping("/defs")
    public ApiResponse<List<WorkflowDef>> defs() {
        return ApiResponse.ok(engine.listDefs());
    }

    @PostMapping("/instances")
    public ApiResponse<WorkflowInstance> start(@RequestBody StartRequest request,
                                               HttpServletRequest httpRequest) {
        return ApiResponse.ok(engine.start(request.getDefKey(), request.getBizType(),
                request.getBizId(), request.getVariables(), currentUserId(httpRequest)));
    }

    @GetMapping("/instances/{id}")
    public ApiResponse<WorkflowInstance> instance(@PathVariable Long id) {
        return ApiResponse.ok(engine.instance(id));
    }

    /** 流程实例状态分布统计（报表）。 */
    @GetMapping("/stats")
    public ApiResponse<java.util.Map<String, Long>> stats() {
        return ApiResponse.ok(engine.instanceStats());
    }

    @GetMapping("/tasks/my")
    public ApiResponse<List<WorkflowTask>> myTasks(HttpServletRequest request) {
        return ApiResponse.ok(engine.myTasks(currentUserId(request)));
    }

    @PostMapping("/tasks/{taskId}/act")
    public ApiResponse<WorkflowInstance> act(@PathVariable Long taskId,
                                             @RequestBody ActRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.ok(engine.act(taskId, currentUserId(request), body.getAction(), body.getComment()));
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

    // ===== 服务间内部接口（X-Internal-Token，直连不经网关） =====

    @org.springframework.web.bind.annotation.PostMapping("/internal/instances")
    public ApiResponse<WorkflowInstance> internalStart(
            @RequestBody StartRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternalToken(token);
        return ApiResponse.ok(engine.start(request.getDefKey(), request.getBizType(),
                request.getBizId(), request.getVariables(), null));
    }

    @org.springframework.web.bind.annotation.GetMapping("/internal/instances/by-biz")
    public ApiResponse<WorkflowInstance> internalByBiz(
            @org.springframework.web.bind.annotation.RequestParam String bizType,
            @org.springframework.web.bind.annotation.RequestParam Long bizId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternalToken(token);
        WorkflowInstance instance = engine.findByBiz(bizType, bizId);
        return ApiResponse.ok(instance);
    }

    @Value("${openforge.internal.token:openforge-internal-dev-token}")
    private String internalToken;

    private void requireInternalToken(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new com.openforge.common.api.BizException(
                    com.openforge.common.api.ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }

    @Data
    public static class DeployRequest {
        @NotBlank
        private String defKey;
        @NotBlank
        private String name;
        @NotBlank
        private String definition;
    }

    @Data
    public static class StartRequest {
        @NotBlank
        private String defKey;
        @NotBlank
        private String bizType;
        private Long bizId;
        private Map<String, Object> variables;
    }

    @Data
    public static class ActRequest {
        @NotBlank
        private String action;  // APPROVE / REJECT
        private String comment;
    }
}

package com.openforge.drawing.client;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.tenant.TenantContext;
import com.openforge.common.tenant.TenantHeaderFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 审签流程客户端（v1.25 设计 §6）：提交评审启流程 + 按业务查实例终态（ECR 同款拉模型）。
 * 模块可用性守卫：workflow 未注册时启动失败给出明确语义（flag 显式启用审签流程就该有 workflow）。
 */
@Component
public class DrawingWorkflowClient {

    public record InstanceView(Long id, String defKey, String state, String currentNode) {
    }

    private static final ParameterizedTypeReference<ApiResponse<InstanceView>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final com.openforge.security.ModuleAvailabilityClient moduleAvailability;

    public DrawingWorkflowClient(@Value("${openforge.workflow.base-url:http://localhost:8084}") String workflowBaseUrl,
                                 @Value("${openforge.security.internal-token:openforge-internal-dev-token}") String internalToken,
                                 com.openforge.security.ModuleAvailabilityClient moduleAvailability) {
        this.restClient = RestClient.builder()
                .baseUrl(workflowBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
        this.moduleAvailability = moduleAvailability;
    }

    public Long startReview(String defKey, Long drawingId) {
        moduleAvailability.ensureAvailable("workflow");
        ApiResponse<InstanceView> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/workflow/internal/instances")
                    .headers(this::forwardTenant)
                    .body(Map.of(
                            "defKey", defKey,
                            "bizType", "DRAWING_REVIEW",
                            "bizId", drawingId,
                            "variables", Map.of()))
                    .retrieve()
                    .body(TYPE);
        } catch (Exception e) {
            throw new com.openforge.common.api.BizException(com.openforge.common.api.ErrorCode.INTERNAL_ERROR,
                    "审签流程启动失败: " + e.getMessage());
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new com.openforge.common.api.BizException(com.openforge.common.api.ErrorCode.INTERNAL_ERROR,
                    "审签流程启动失败: " + (response == null ? "流程服务无响应" : response.getMessage()));
        }
        return response.getData().id();
    }

    /** 按业务查在途实例（无则返回 null；流程服务不可用降级 null——状态展示退回轻量态）。 */
    public InstanceView findByBiz(Long drawingId) {
        ApiResponse<InstanceView> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/workflow/internal/instances/by-biz")
                            .queryParam("bizType", "DRAWING_REVIEW")
                            .queryParam("bizId", drawingId)
                            .build())
                    .headers(this::forwardTenant)
                    .retrieve()
                    .body(TYPE);
        } catch (Exception e) {
            return null;
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return response.getData();
    }

    private void forwardTenant(org.springframework.http.HttpHeaders headers) {
        headers.set(TenantHeaderFilter.HEADER_USER_TENANT,
                String.valueOf(TenantContext.getTenantId()));
    }
}

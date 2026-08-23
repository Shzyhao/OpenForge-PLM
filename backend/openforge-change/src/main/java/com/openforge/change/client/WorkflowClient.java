package com.openforge.change.client;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** 流程引擎客户端：启动实例 / 按业务查实例状态。 */
@Component
public class WorkflowClient {

    public record InstanceView(Long id, String defKey, String state, String currentNode) {
    }

    private static final ParameterizedTypeReference<ApiResponse<InstanceView>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public WorkflowClient(@Value("${openforge.workflow.base-url}") String workflowBaseUrl,
                          @Value("${openforge.security.internal-token}") String internalToken) {
        this.restClient = RestClient.builder()
                .baseUrl(workflowBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public Long start(String defKey, String bizType, Long bizId, Map<String, Object> variables) {
        ApiResponse<InstanceView> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/workflow/internal/instances")
                    .body(Map.of(
                            "defKey", defKey,
                            "bizType", bizType,
                            "bizId", bizId == null ? 0 : bizId,
                            "variables", variables == null ? Map.of() : variables))
                    .retrieve()
                    .body(TYPE);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "流程服务不可用: " + e.getMessage());
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    response == null ? "流程服务无响应" : "启动流程失败: " + response.getMessage());
        }
        return response.getData().id();
    }

    /** 按业务对象查在途实例（无则返回 null）。 */
    public InstanceView findByBiz(String bizType, Long bizId) {
        ApiResponse<InstanceView> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/workflow/internal/instances/by-biz")
                            .queryParam("bizType", bizType)
                            .queryParam("bizId", bizId)
                            .build())
                    .retrieve()
                    .body(TYPE);
        } catch (Exception e) {
            return null; // 状态展示降级：流程服务不可用时仅展示 ECR 自身状态
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return response.getData();
    }
}

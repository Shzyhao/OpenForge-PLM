package com.openforge.change.client;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 编号引擎客户端（与 material 的 NumberClient 同构，M3 抽公共模块）。 */
@Component("changeNumberClient")
public class NumberClient {

    private static final ParameterizedTypeReference<ApiResponse<String>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public NumberClient(@Value("${openforge.security.auth-base-url}") String authBaseUrl,
                        @Value("${openforge.security.internal-token}") String internalToken) {
        this.restClient = RestClient.builder()
                .baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    public String next(String ruleKey) {
        ApiResponse<String> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/internal/numbers/next/{ruleKey}", ruleKey)
                    .retrieve()
                    .body(TYPE);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "编号服务不可用: " + e.getMessage());
        }
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    response == null ? "编号服务无响应" : "取号失败: " + response.getMessage());
        }
        return response.getData();
    }
}

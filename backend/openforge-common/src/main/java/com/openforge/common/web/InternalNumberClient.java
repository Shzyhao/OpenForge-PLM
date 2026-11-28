package com.openforge.common.web;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * 编号引擎内部客户端基座（v1.28 P5#26）：五域（change/doc/drawing/material/project）同构拷贝收敛。
 * 子类以域前缀 bean 名装配（mono 单 classpath 防冲突，先例 changeNumberClient）：
 * <pre>{@code
 * @Component("changeNumberClient")
 * public class NumberClient extends InternalNumberClient {
 *     public NumberClient(@Value("${openforge.security.auth-base-url}") String baseUrl,
 *                         @Value("${openforge.security.internal-token}") String token) { super(baseUrl, token); }
 * }
 * }</pre>
 */
public class InternalNumberClient {

    private static final ParameterizedTypeReference<ApiResponse<String>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public InternalNumberClient(String authBaseUrl, String internalToken) {
        this.restClient = InternalRestClientFactory.builder()
                .baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /** 取下一号；下游不可用/拒绝时抛 BizException（文案与原五域拷贝一致）。 */
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

package com.openforge.connector.controller;

import com.openforge.common.api.ApiResponse;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.service.AiProviderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ai-gateway 内部配置拉取端点（集成编排器 MVP 设计 §12.1）：enabled 供应商按降级链排序，
 * 含解密后的 baseUrl/apiKey/model——仅内部令牌可达；ai-gateway 启动加载 + 30s 轮询。
 */
@RestController
@RequestMapping("/internal/ai-provider")
public class AiProviderInternalController {

    private final AiProviderService providerService;
    private final String internalToken;

    public AiProviderInternalController(AiProviderService providerService,
                                        @Value("${openforge.security.internal-token:openforge-internal-dev-token}")
                                        String internalToken) {
        this.providerService = providerService;
        this.internalToken = internalToken;
    }

    @GetMapping("/chain")
    public ApiResponse<java.util.List<Map<String, Object>>> chain(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternal(token);
        java.util.List<AiProviderService.DecryptedProvider> chain = providerService.enabledChain();
        return ApiResponse.ok(chain.stream()
                .map(p -> Map.<String, Object>of(
                        "baseUrl", p.baseUrl(),
                        "apiKey", p.apiKey(),
                        "model", p.model(),
                        "timeoutMs", p.timeoutMs() == null ? 60_000 : p.timeoutMs()))
                .collect(Collectors.toList()));
    }

    private void requireInternal(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "内部接口令牌无效");
        }
    }
}

package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import com.openforge.connector.crypto.AesGcmCipher;
import com.openforge.connector.dto.AiProviderResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.ProviderTestResponse;
import com.openforge.connector.dto.SaveAiProviderRequest;
import com.openforge.connector.entity.AiProvider;
import com.openforge.connector.mapper.AiProviderMapper;
import com.openforge.connector.security.EgressGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * AI 供应商服务（集成编排器 MVP 设计 §12.1 P2-1）：LLM 供应商即"AI 连接器"——
 * base_url + api_key（AES-GCM 复用凭据加密体系）+ 模型 + 降级链优先级。
 * 连通性测试由 Java 侧代理执行（key 不出服务）；出站一律过 EgressGuard。
 */
@Slf4j
@Service
public class AiProviderService {

    private final AiProviderMapper providerMapper;
    private final AesGcmCipher cipher;
    private final EgressGuard egressGuard;
    private final HttpClient httpClient;

    public AiProviderService(AiProviderMapper providerMapper, AesGcmCipher cipher, EgressGuard egressGuard) {
        this.providerMapper = providerMapper;
        this.cipher = cipher;
        this.egressGuard = egressGuard;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Transactional
    public AiProviderResponse create(SaveAiProviderRequest request, Long userId) {
        checkCode(request.getProviderCode());
        requireUrl(request.getBaseUrl());
        requireModel(request.getModel());
        requireKey(request.getApiKey());
        Long existed = providerMapper.selectCount(new LambdaQueryWrapper<AiProvider>()
                .eq(AiProvider::getProviderCode, request.getProviderCode()));
        if (existed > 0) {
            throw new BizException(ErrorCode.CONN_CODE_EXISTS, "供应商编码已存在: " + request.getProviderCode());
        }
        if (!cipher.available()) {
            throw new BizException(ErrorCode.CONN_MASTER_KEY_MISSING);
        }
        AiProvider provider = new AiProvider();
        applyRequest(provider, request);
        provider.setApiKeyEnc(cipher.encrypt(request.getApiKey()));
        provider.setTenantId(TenantContext.getTenantId());
        provider.setCreatedBy(userId);
        providerMapper.insert(provider);
        return AiProviderResponse.from(provider);
    }

    /** apiKey 留空 = 仅更新其余字段（key 不回显、无法原样读回）。 */
    @Transactional
    public AiProviderResponse update(Long id, SaveAiProviderRequest request, Long userId) {
        AiProvider provider = requireProvider(id);
        applyRequest(provider, request);
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            provider.setApiKeyEnc(cipher.encrypt(request.getApiKey()));
        }
        provider.setUpdatedBy(userId);
        providerMapper.updateById(provider);
        return AiProviderResponse.from(provider);
    }

    public PageResponse<AiProviderResponse> page(long page, long pageSize) {
        Page<AiProvider> result = providerMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<AiProvider>()
                        .orderByAsc(AiProvider::getPriority)
                        .orderByAsc(AiProvider::getId));
        return PageResponse.from(result, AiProviderResponse::from);
    }

    @Transactional
    public void delete(Long id) {
        requireProvider(id);
        providerMapper.deleteById(id);
    }

    /**
     * 连通性测试：GET {baseUrl}/models（OpenAI 兼容），Bearer 认证，2xx = 可用。
     * 出站过 EgressGuard；apiKey 仅内存态。
     */
    public ProviderTestResponse test(Long id) {
        AiProvider provider = requireProvider(id);
        String url = trimTrailingSlash(provider.getBaseUrl()) + "/models";
        egressGuard.check(url);
        long start = System.currentTimeMillis();
        ProviderTestResponse response = new ProviderTestResponse();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.min(provider.getTimeoutMs(), 30_000)))
                    .header("Authorization", "Bearer " + cipher.decrypt(provider.getApiKeyEnc()))
                    .GET()
                    .build();
            HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            response.setHttpStatus(httpResponse.statusCode());
            response.setStatus(httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300
                    ? "SUCCESS" : "FAILED");
            if (response.getStatus().equals("FAILED")) {
                response.setError("上游返回 " + httpResponse.statusCode());
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            response.setStatus("FAILED");
            response.setError("连接失败: " + e.getClass().getSimpleName());
        }
        response.setDurationMs(System.currentTimeMillis() - start);
        return response;
    }

    /** ai-gateway 内部拉取（X-Internal-Token 保护，controller 校验）：enabled 供应商按降级链排序，含解密 key。 */
    public List<DecryptedProvider> enabledChain() {
        return providerMapper.selectList(new LambdaQueryWrapper<AiProvider>()
                        .eq(AiProvider::getEnabled, 1)
                        .orderByAsc(AiProvider::getPriority)
                        .orderByAsc(AiProvider::getId))
                .stream()
                .map(p -> new DecryptedProvider(p.getBaseUrl(), cipher.decrypt(p.getApiKeyEnc()),
                        p.getModel(), p.getTimeoutMs()))
                .toList();
    }

    private void applyRequest(AiProvider provider, SaveAiProviderRequest request) {
        if (request.getProviderCode() != null) {
            provider.setProviderCode(request.getProviderCode());
        }
        if (request.getProviderName() != null) {
            provider.setProviderName(request.getProviderName());
        }
        if (request.getBaseUrl() != null) {
            provider.setBaseUrl(request.getBaseUrl().trim());
        }
        if (request.getModel() != null) {
            provider.setModel(request.getModel().trim());
        }
        provider.setTimeoutMs(request.getTimeoutMs() == null ? 60_000
                : Math.max(1_000, Math.min(request.getTimeoutMs(), 120_000)));
        provider.setEnabled(request.getEnabled() == null || request.getEnabled() == 1 ? 1 : 0);
        provider.setPriority(request.getPriority() == null ? 100 : request.getPriority());
    }

    private AiProvider requireProvider(Long id) {
        AiProvider provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new BizException(ErrorCode.CONN_NOT_FOUND, "AI 供应商不存在");
        }
        return provider;
    }

    private void checkCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "providerCode 须匹配 ^[a-z][a-z0-9_]{2,63}$: " + code);
        }
    }

    private void requireUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "baseUrl 不能为空");
        }
        String scheme = URI.create(baseUrl.trim()).getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "baseUrl 仅支持 http/https");
        }
    }

    private void requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "model 不能为空");
        }
    }

    private void requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "apiKey 不能为空");
        }
    }

    private String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 内部配置视图（解密后；仅经 /internal 端点 + 内部令牌提供给 ai-gateway）。 */
    public record DecryptedProvider(String baseUrl, String apiKey, String model, Integer timeoutMs) {
    }
}

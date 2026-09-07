package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import com.openforge.connector.crypto.AesGcmCipher;
import com.openforge.connector.dto.CredentialResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.SaveCredentialRequest;
import com.openforge.connector.entity.ConnCredential;
import com.openforge.connector.mapper.ConnCredentialMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 凭据服务（集成编排器 MVP 设计 §7）：写时加密、永不回显明文。
 * 主密钥未配置时创建/更新凭据直接拒绝（CONN_MASTER_KEY_MISSING，不降级明文）。
 */
@Service
public class CredentialService {

    private static final Set<String> MVP_AUTH_TYPES = Set.of("BASIC", "BEARER", "API_KEY_HEADER", "JDBC_PASSWORD");

    private final ConnCredentialMapper credentialMapper;
    private final AesGcmCipher cipher;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.openforge.connector.client.AuthAuditClient auditClient;

    public CredentialService(ConnCredentialMapper credentialMapper, AesGcmCipher cipher,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             com.openforge.connector.client.AuthAuditClient auditClient) {
        this.credentialMapper = credentialMapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.auditClient = auditClient;
    }

    @Transactional
    public CredentialResponse create(SaveCredentialRequest request, Long userId) {
        checkCode(request.getCredCode());
        checkAuthType(request.getAuthType());
        requireSecret(request.getSecret());
        Long existed = credentialMapper.selectCount(new LambdaQueryWrapper<ConnCredential>()
                .eq(ConnCredential::getCredCode, request.getCredCode()));
        if (existed > 0) {
            throw new BizException(ErrorCode.CONN_CRED_CODE_EXISTS);
        }
        ConnCredential credential = new ConnCredential();
        credential.setCredCode(request.getCredCode());
        credential.setCredName(request.getCredName());
        credential.setAuthType(request.getAuthType());
        credential.setExtraJson(request.getExtra());
        credential.setSecretCipher(cipher.encrypt(request.getSecret()));
        credential.setTenantId(TenantContext.getTenantId());
        credential.setCreatedBy(userId);
        credentialMapper.insert(credential);
        auditClient.record(userId, "CONN_CRED_CREATE", "CREDENTIAL", credential.getCredCode(),
                "新建凭据 " + credential.getCredName() + "（" + credential.getAuthType() + "）");
        return CredentialResponse.from(credential);
    }

    /** secret 留空 = 仅更新名称/认证类型/附加信息（凭据值不回显，也无法原样读回）。 */
    @Transactional
    public CredentialResponse update(Long id, SaveCredentialRequest request, Long userId) {
        ConnCredential credential = requireCredential(id);
        if (request.getCredName() != null) {
            credential.setCredName(request.getCredName());
        }
        if (request.getAuthType() != null) {
            checkAuthType(request.getAuthType());
            credential.setAuthType(request.getAuthType());
        }
        if (request.getExtra() != null) {
            credential.setExtraJson(request.getExtra());
        }
        if (request.getSecret() != null && !request.getSecret().isBlank()) {
            credential.setSecretCipher(cipher.encrypt(request.getSecret()));
        }
        credential.setUpdatedBy(userId);
        credentialMapper.updateById(credential);
        auditClient.record(userId, "CONN_CRED_UPDATE", "CREDENTIAL", credential.getCredCode(),
                "更新凭据 " + credential.getCredName()
                        + (request.getSecret() != null && !request.getSecret().isBlank() ? "（含密值轮换）" : ""));
        return CredentialResponse.from(credential);
    }

    public PageResponse<CredentialResponse> page(long page, long pageSize) {
        Page<ConnCredential> result = credentialMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ConnCredential>().orderByDesc(ConnCredential::getId));
        return PageResponse.from(result, CredentialResponse::from);
    }

    @Transactional
    public void delete(Long id, Long userId) {
        ConnCredential credential = requireCredential(id);
        credentialMapper.deleteById(id);
        auditClient.record(userId, "CONN_CRED_DELETE", "CREDENTIAL", credential.getCredCode(),
                "删除凭据 " + credential.getCredName() + "（" + credential.getAuthType() + "）");
    }

    /** 运行时解密入口（仅 ConnectorRuntime 调用；密文不出服务层）。 */
    ResolvedSecret resolveByCode(String credCode) {
        ConnCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<ConnCredential>()
                .eq(ConnCredential::getCredCode, credCode));
        if (credential == null) {
            throw new BizException(ErrorCode.CONN_CRED_NOT_FOUND, "凭据不存在: " + credCode);
        }
        String headerName = null;
        if (credential.getExtraJson() != null && !credential.getExtraJson().isBlank()) {
            try {
                var node = objectMapper.readTree(credential.getExtraJson());
                if (node.hasNonNull("headerName")) {
                    headerName = node.get("headerName").asText();
                }
            } catch (Exception ignored) {
                // extraJson 非法按无附加信息处理
            }
        }
        return new ResolvedSecret(credential.getAuthType(), cipher.decrypt(credential.getSecretCipher()),
                headerName);
    }

    private void checkAuthType(String authType) {
        if (!MVP_AUTH_TYPES.contains(authType)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "认证类型仅支持 BASIC/BEARER/API_KEY_HEADER: " + authType);
        }
    }

    private ConnCredential requireCredential(Long id) {
        ConnCredential credential = credentialMapper.selectById(id);
        if (credential == null) {
            throw new BizException(ErrorCode.CONN_CRED_NOT_FOUND);
        }
        return credential;
    }

    private void checkCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "credCode 须匹配 ^[a-z][a-z0-9_]{2,63}$: " + code);
        }
    }

    private void requireSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "secret 不能为空");
        }
    }
}

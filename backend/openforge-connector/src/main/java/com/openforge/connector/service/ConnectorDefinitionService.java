package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import com.openforge.connector.dto.ConnDetailResponse;
import com.openforge.connector.dto.ConnSummaryResponse;
import com.openforge.connector.dto.InvokeRequest;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.dto.PageResponse;
import com.openforge.connector.dto.SaveConnRequest;
import com.openforge.connector.entity.ConnDefinition;
import com.openforge.connector.entity.ConnDefinitionVersion;
import com.openforge.connector.mapper.ConnDefinitionMapper;
import com.openforge.connector.mapper.ConnDefinitionVersionMapper;
import com.openforge.connector.mapper.ConnExecLogMapper;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spi.ConnectorResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

/**
 * 连接器定义服务（集成编排器 MVP 设计 §3.3）：CRUD + 版本化发布 + 停用 + 试运行。
 * 发布 = 兼容性校验 → 不可变版本快照 → 主档 PUBLISHED/version+1 →
 * afterCommit 放入运行时缓存 + connector.published 事件（未启用 MQ 仅缓存生效，
 * MVP 无跨服务消费者，广播为 P2 事件触发预留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorDefinitionService {

    private final ConnDefinitionMapper definitionMapper;
    private final ConnDefinitionVersionMapper versionMapper;
    private final ConnExecLogMapper execLogMapper;
    private final CredentialService credentialService;
    private final PublishedConnCache publishedConnCache;
    private final ConnectorRuntime connectorRuntime;
    private final ObjectMapper objectMapper;
    private final com.openforge.common.event.EventPublisher eventPublisher;

    @Transactional
    public ConnDetailResponse create(SaveConnRequest request, Long userId) {
        ConnectorSpecs.checkType(request.getConnType());
        checkCode(request.getConnCode());
        Long existed = definitionMapper.selectCount(new LambdaQueryWrapper<ConnDefinition>()
                .eq(ConnDefinition::getConnCode, request.getConnCode()));
        if (existed > 0) {
            throw new BizException(ErrorCode.CONN_CODE_EXISTS);
        }
        ConnDefinition def = new ConnDefinition();
        def.setConnCode(request.getConnCode());
        def.setConnName(request.getConnName());
        def.setConnType(request.getConnType());
        def.setDescription(request.getDescription());
        def.setStatus("DRAFT");
        def.setCurrentVersion(0);
        def.setSpecJson(normalizeAndValidate(request));
        def.setTenantId(TenantContext.getTenantId());
        def.setCreatedBy(userId);
        definitionMapper.insert(def);
        return detail(def.getId());
    }

    /** 仅 DRAFT/DISABLED 可改（已发布先停用）；connCode 不可变。 */
    @Transactional
    public ConnDetailResponse update(Long id, SaveConnRequest request, Long userId) {
        ConnDefinition def = requireDefinition(id);
        if ("PUBLISHED".equals(def.getStatus())) {
            throw new BizException(ErrorCode.CONN_PUBLISHED_LOCKED);
        }
        ConnectorSpecs.checkType(request.getConnType());
        def.setConnName(request.getConnName());
        def.setConnType(request.getConnType());
        def.setDescription(request.getDescription());
        def.setSpecJson(normalizeAndValidate(request));
        def.setUpdatedBy(userId);
        definitionMapper.updateById(def);
        return detail(id);
    }

    public PageResponse<ConnSummaryResponse> page(long page, long pageSize) {
        Page<ConnDefinition> result = definitionMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ConnDefinition>().orderByDesc(ConnDefinition::getId));
        return PageResponse.from(result, ConnSummaryResponse::from);
    }

    public ConnDetailResponse detail(Long id) {
        ConnDefinition def = requireDefinition(id);
        ConnDetailResponse response = new ConnDetailResponse();
        response.setId(def.getId());
        response.setConnCode(def.getConnCode());
        response.setConnName(def.getConnName());
        response.setConnType(def.getConnType());
        response.setStatus(def.getStatus());
        response.setCurrentVersion(def.getCurrentVersion());
        response.setDescription(def.getDescription());
        response.setSpec(fromJson(def.getSpecJson()));
        response.setVersions(versionMapper.selectList(new LambdaQueryWrapper<ConnDefinitionVersion>()
                        .eq(ConnDefinitionVersion::getConnId, id)
                        .orderByDesc(ConnDefinitionVersion::getVersion))
                .stream().map(ConnDetailResponse::versionOf).toList());
        return response;
    }

    /** 已发布需先停用才可删除。 */
    @Transactional
    public void delete(Long id) {
        ConnDefinition def = requireDefinition(id);
        if ("PUBLISHED".equals(def.getStatus())) {
            throw new BizException(ErrorCode.CONN_PUBLISHED_LOCKED, "已发布连接器不可删除，请先停用");
        }
        definitionMapper.deleteById(id);
    }

    @Transactional
    public Map<String, Object> publish(Long id, Long userId) {
        ConnDefinition def = requireDefinition(id);
        if ("PUBLISHED".equals(def.getStatus())) {
            throw new BizException(ErrorCode.CONN_PUBLISHED_LOCKED,
                    "连接器已发布，如需变更请先停用再编辑发布");
        }
        // 发布前再校验一次 spec 与凭据引用（编辑与发布间可能发生凭据删除）
        validateSpec(def.getConnType(), fromJson(def.getSpecJson()));
        int version = def.getCurrentVersion() + 1;
        ConnDefinitionVersion snapshot = new ConnDefinitionVersion();
        snapshot.setConnId(id);
        snapshot.setVersion(version);
        snapshot.setSpecJson(def.getSpecJson());
        snapshot.setPublishedBy(userId);
        snapshot.setTenantId(def.getTenantId());
        versionMapper.insert(snapshot);

        def.setStatus("PUBLISHED");
        def.setCurrentVersion(version);
        def.setUpdatedBy(userId);
        definitionMapper.updateById(def);

        boolean viaMq = eventPublisher.publish("openforge-connector", "connector.published", Map.of(
                "connCode", def.getConnCode(),
                "connType", def.getConnType(),
                "version", version));
        if (!viaMq) {
            log.info("事件总线未启用，connector.published 仅缓存生效: connCode={}, version={}",
                    def.getConnCode(), version);
        }
        PublishedConn published = new PublishedConn(id, def.getConnCode(), def.getConnType(),
                version, def.getSpecJson());
        afterCommit(() -> publishedConnCache.put(published));

        return Map.of("connId", id, "connCode", def.getConnCode(),
                "status", def.getStatus(), "version", version);
    }

    @Transactional
    public Map<String, Object> disable(Long id, Long userId) {
        ConnDefinition def = requireDefinition(id);
        if ("DISABLED".equals(def.getStatus())) {
            return Map.of("connId", id, "status", def.getStatus());
        }
        def.setStatus("DISABLED");
        def.setUpdatedBy(userId);
        definitionMapper.updateById(def);
        afterCommit(() -> publishedConnCache.evict(def.getConnCode()));
        return Map.of("connId", id, "status", "DISABLED");
    }

    /** 试运行：设计态用当前 spec（含 DRAFT），发布态用主档 spec（与最新快照一致）。 */
    public InvokeResponse test(Long id, InvokeRequest request) {
        ConnDefinition def = requireDefinition(id);
        long start = System.currentTimeMillis();
        ConnectorResult result = connectorRuntime.execute(def.getConnType(), def.getSpecJson(),
                def.getCurrentVersion(), def.getId(),
                request.getParams() == null ? Map.of() : request.getParams(), "MANUAL");
        return InvokeResponse.from(result, System.currentTimeMillis() - start);
    }

    /** 运行时调用入口（仅已发布；trigger=API），委托运行时按缓存→回源执行。 */
    public InvokeResponse invoke(String connCode, Map<String, Object> params) {
        return connectorRuntime.invokePublished(connCode, params, "API");
    }

    /** 执行日志分页（已脱敏；日志表带 tenant_id，租户行级过滤自动生效）。 */
    public PageResponse<com.openforge.connector.dto.ExecLogResponse> execLogs(Long connId, long page, long pageSize) {
        requireDefinition(connId);
        var result = execLogMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<com.openforge.connector.entity.ConnExecLog>()
                        .eq(com.openforge.connector.entity.ConnExecLog::getConnId, connId)
                        .orderByDesc(com.openforge.connector.entity.ConnExecLog::getId));
        return PageResponse.from(result, com.openforge.connector.dto.ExecLogResponse::from);
    }

    // ===== 内部 =====

    /** spec 规范化（canonical JSON 落库）+ 校验（含凭据引用存在性）。 */
    private String normalizeAndValidate(SaveConnRequest request) {
        validateSpec(request.getConnType(), request.getSpec());
        try {
            return objectMapper.writeValueAsString(request.getSpec());
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "spec 序列化失败");
        }
    }

    /** spec 设计态校验（含凭据引用存在性）：HTTP/JDBC 分类型解析，失败抛 CONN_SPEC_INVALID。 */
    private void validateSpec(String connType, Map<String, Object> specMap) {
        ConnectorSpecs.checkType(connType);
        String credRef = switch (connType) {
            case ConnectorSpecs.TYPE_HTTP_REST ->
                    specMap.get("credentialRef") == null ? null : String.valueOf(specMap.get("credentialRef"));
            case ConnectorSpecs.TYPE_JDBC_READONLY ->
                    specMap.get("passwordRef") == null ? null : String.valueOf(specMap.get("passwordRef"));
            default -> null;
        };
        boolean credExists = true;
        if (credRef != null) {
            try {
                credentialService.resolveByCode(credRef);
            } catch (BizException e) {
                if (e.getErrorCode() != ErrorCode.CONN_CRED_NOT_FOUND) {
                    throw e;
                }
                credExists = false;
            }
        }
        switch (connType) {
            case ConnectorSpecs.TYPE_HTTP_REST ->
                    ConnectorSpecs.parseHttpRest(specMap, objectMapper, credExists);
            case ConnectorSpecs.TYPE_JDBC_READONLY ->
                    ConnectorSpecs.parseJdbcReadonly(specMap, objectMapper, credExists);
            default -> throw new BizException(ErrorCode.CONN_SPEC_INVALID, "不支持的连接器类型: " + connType);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "spec JSON 解析失败");
        }
    }

    private ConnDefinition requireDefinition(Long id) {
        ConnDefinition def = definitionMapper.selectById(id);
        if (def == null) {
            throw new BizException(ErrorCode.CONN_NOT_FOUND);
        }
        return def;
    }

    private void checkCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "connCode 须匹配 ^[a-z][a-z0-9_]{2,63}$: " + code);
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

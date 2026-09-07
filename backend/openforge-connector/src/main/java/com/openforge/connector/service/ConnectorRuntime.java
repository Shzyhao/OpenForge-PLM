package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.entity.ConnDefinition;
import com.openforge.connector.entity.ConnExecLog;
import com.openforge.connector.mapper.ConnDefinitionMapper;
import com.openforge.connector.mapper.ConnExecLogMapper;
import com.openforge.connector.security.EgressGuard;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spec.JdbcReadonlySpec;
import com.openforge.connector.spi.ConnectorResult;
import com.openforge.connector.spi.ConnectorSpi;
import com.openforge.connector.spi.ResolvedCredential;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 连接器运行时统一执行入口（集成编排器 MVP 设计 §3.3）：
 * spec 解析 → 参数校验 → 出站白名单（HTTP；BLOCKED 落日志）→ 凭据解密 → SPI 执行 →
 * 执行日志留痕（同步一条 insert，凭据/敏感值已在源头脱敏）。
 * 调用方错误（缺参/白名单拦截/凭据缺失）以 BizException 上抛（finally 照常落日志）；
 * 上游失败与系统异常收敛为失败结果。
 */
@Slf4j
@Service
public class ConnectorRuntime {

    private final List<ConnectorSpi> spis;
    private final CredentialService credentialService;
    private final EgressGuard egressGuard;
    private final PublishedConnCache publishedConnCache;
    private final ConnDefinitionMapper definitionMapper;
    private final ConnExecLogMapper execLogMapper;
    private final ObjectMapper objectMapper;

    public ConnectorRuntime(List<ConnectorSpi> spis, CredentialService credentialService,
                            EgressGuard egressGuard, PublishedConnCache publishedConnCache,
                            ConnDefinitionMapper definitionMapper, ConnExecLogMapper execLogMapper,
                            ObjectMapper objectMapper) {
        this.spis = spis;
        this.credentialService = credentialService;
        this.egressGuard = egressGuard;
        this.publishedConnCache = publishedConnCache;
        this.definitionMapper = definitionMapper;
        this.execLogMapper = execLogMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void checkSpiUnique() {
        long distinct = spis.stream().map(ConnectorSpi::type).distinct().count();
        if (distinct != spis.size()) {
            throw new IllegalStateException("ConnectorSpi 类型重复注册");
        }
    }

    /**
     * 运行时调用（按 connCode）：仅已发布连接器可调用（停用 6005 / 未发布 6004 / 不存在 6002）。
     * 缓存命中直接执行；未命中回源并回填。
     */
    public InvokeResponse invokePublished(String connCode, Map<String, Object> params, String triggerType) {
        PublishedConn published = publishedConnCache.get(connCode);
        if (published == null) {
            published = loadPublished(connCode);
            publishedConnCache.put(published);
        }
        long start = System.currentTimeMillis();
        ConnectorResult result = execute(published.connType(), published.specJson(),
                published.version(), published.connId(), params, triggerType);
        return InvokeResponse.from(result, System.currentTimeMillis() - start);
    }

    /** 设计态/管理面执行（specJson 来自主档；version 传主档 currentVersion）。 */
    public ConnectorResult execute(String connType, String specJson, int version,
                                   Long connId, Map<String, Object> params, String triggerType) {
        long start = System.currentTimeMillis();
        String status = "FAILED";
        Integer httpStatus = null;
        Integer rowsReturned = null;
        String error = null;
        try {
            ParsedSpec parsed = parseSpec(connType, specJson);
            ConnectorSpecs.checkRequiredParams(parsed.parameterSchema(), params);
            if (ConnectorSpecs.TYPE_HTTP_REST.equals(connType)) {
                egressGuard.check(((HttpRestSpec) parsed.spec()).url());
            }
            ResolvedCredential resolved = parsed.credentialRef() == null ? null
                    : toSpiCredential(credentialService.resolveByCode(parsed.credentialRef()));
            ConnectorResult result = spiOf(connType).execute(new com.openforge.connector.spi.ConnectorExecution(
                    parsed.httpSpec(), parsed.jdbcSpec(), params, resolved));
            status = result.success() ? "SUCCESS" : "FAILED";
            httpStatus = result.httpStatus();
            rowsReturned = result.rowsReturned();
            error = result.error();
            return result;
        } catch (BizException e) {
            status = e.getErrorCode() == ErrorCode.CONN_EGRESS_BLOCKED ? "BLOCKED" : "FAILED";
            error = e.getMessage();
            throw e;
        } catch (Exception e) {
            log.error("连接器执行异常 connId={}", connId, e);
            error = "连接器执行异常";
            return ConnectorResult.fail(null, error);
        } finally {
            writeLog(connId, version, triggerType, status, httpStatus, rowsReturned, error,
                    System.currentTimeMillis() - start);
        }
    }

    private PublishedConn loadPublished(String connCode) {
        ConnDefinition def = definitionMapper.selectOne(new LambdaQueryWrapper<ConnDefinition>()
                .eq(ConnDefinition::getConnCode, connCode));
        if (def == null) {
            throw new BizException(ErrorCode.CONN_NOT_FOUND, "连接器不存在: " + connCode);
        }
        switch (def.getStatus()) {
            case "PUBLISHED" -> {
                return new PublishedConn(def.getId(), def.getConnCode(), def.getConnType(),
                        def.getCurrentVersion(), def.getSpecJson());
            }
            case "DISABLED" -> throw new BizException(ErrorCode.CONN_DISABLED, "连接器已停用: " + connCode);
            default -> throw new BizException(ErrorCode.CONN_NOT_PUBLISHED, "连接器未发布: " + connCode);
        }
    }

    /** 按 connType 解析 spec（失败抛 CONN_SPEC_INVALID），并提取凭据引用与参数 schema。
     *  凭据存在性不做预检（省一次查询）——执行期 resolveByCode 以 CONN_CRED_NOT_FOUND 明确承接。 */
    private ParsedSpec parseSpec(String connType, String specJson) {
        Map<String, Object> specMap;
        try {
            specMap = objectMapper.readValue(specJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "spec JSON 解析失败");
        }
        return switch (connType) {
            case ConnectorSpecs.TYPE_HTTP_REST -> {
                HttpRestSpec spec = ConnectorSpecs.parseHttpRest(specMap, objectMapper, true);
                yield new ParsedSpec(spec, null, spec.credentialRef(), spec.parameterSchema());
            }
            case ConnectorSpecs.TYPE_JDBC_READONLY -> {
                JdbcReadonlySpec spec = ConnectorSpecs.parseJdbcReadonly(specMap, objectMapper, true);
                yield new ParsedSpec(null, spec, spec.passwordRef(), spec.parameterSchema());
            }
            default -> throw new BizException(ErrorCode.CONN_SPEC_INVALID, "不支持的连接器类型: " + connType);
        };
    }

    private ConnectorSpi spiOf(String connType) {
        return spis.stream().filter(s -> s.type().equals(connType)).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "无该类型的连接器实现: " + connType));
    }

    private ResolvedCredential toSpiCredential(ResolvedSecret secret) {
        return new ResolvedCredential(secret.authType(), secret.secret(), secret.headerName());
    }

    private void writeLog(Long connId, int version, String triggerType, String status,
                          Integer httpStatus, Integer rowsReturned, String error, long durationMs) {
        try {
            ConnExecLog execLog = new ConnExecLog();
            execLog.setConnId(connId);
            execLog.setConnVersion(version);
            execLog.setTriggerType(triggerType);
            execLog.setStatus(status);
            execLog.setHttpStatus(httpStatus);
            execLog.setRowsReturned(rowsReturned);
            execLog.setDurationMs(durationMs);
            execLog.setErrorMsg(truncate(error));
            execLog.setTraceId(MDC.get(com.openforge.common.trace.TraceIdFilter.MDC_KEY));
            execLogMapper.insert(execLog);
        } catch (Exception e) {
            log.warn("执行日志写入失败 connId={}", connId, e);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }

    /** 解析产物：typed spec + 凭据引用 + 参数 schema（运行时共用）。 */
    private record ParsedSpec(HttpRestSpec httpSpec, JdbcReadonlySpec jdbcSpec,
                              String credentialRef, Map<String, Object> parameterSchema) {

        Object spec() {
            return httpSpec != null ? httpSpec : jdbcSpec;
        }
    }
}

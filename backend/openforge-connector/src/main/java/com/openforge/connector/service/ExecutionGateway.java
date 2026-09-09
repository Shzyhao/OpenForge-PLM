package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.entity.ConnDefinition;
import com.openforge.connector.mapper.ConnDefinitionMapper;
import com.openforge.connector.spec.ChainSpecs;
import com.openforge.connector.spec.ConnectorSpecs;
import com.openforge.connector.spi.ConnectorResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 执行统一分派（P3 刀1，§14.4）：单步走 ConnectorRuntime（一步一日志），链（CHAIN/
 * schemaVersion=2）走 ChainExecutor（一链一日志 + steps_json）。
 * 调用方：invoke API、设计态试运行、触发分发（EVENT/CRON）——链触发自刀1 起即生效
 * （EVENT/CRON 作用于整链）。
 */
@Service
public class ExecutionGateway {

    private final PublishedConnCache publishedConnCache;
    private final ConnDefinitionMapper definitionMapper;
    private final ConnectorRuntime runtime;
    private final ChainExecutor chainExecutor;
    private final ObjectMapper objectMapper;

    public ExecutionGateway(PublishedConnCache publishedConnCache,
                            ConnDefinitionMapper definitionMapper,
                            ConnectorRuntime runtime,
                            ChainExecutor chainExecutor,
                            ObjectMapper objectMapper) {
        this.publishedConnCache = publishedConnCache;
        this.definitionMapper = definitionMapper;
        this.runtime = runtime;
        this.chainExecutor = chainExecutor;
        this.objectMapper = objectMapper;
    }

    /** 运行时调用（按 connCode）：仅已发布可调用（停用 6005 / 未发布 6004 / 不存在 6002）。 */
    public InvokeResponse invokePublished(String connCode, Map<String, Object> params, String triggerType) {
        PublishedConn published = publishedConnCache.get(connCode);
        if (published == null) {
            published = loadPublished(connCode);
            publishedConnCache.put(published);
        }
        return execute(published.connType(), published.specJson(), published.version(),
                published.connId(), params, triggerType);
    }

    /** 统一执行入口：链/单步自动分派（设计态试运行与触发分发共用）。 */
    public InvokeResponse execute(String connType, String specJson, int version, Long connId,
                                  Map<String, Object> params, String triggerType) {
        if (ConnectorSpecs.TYPE_CHAIN.equals(connType) || ChainSpecs.isChainJson(specJson, objectMapper)) {
            return chainExecutor.execute(specJson, version, connId, params, triggerType);
        }
        long start = System.currentTimeMillis();
        ConnectorResult result = runtime.execute(connType, specJson, version, connId, params, triggerType);
        return InvokeResponse.from(result, System.currentTimeMillis() - start);
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
}

package com.openforge.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.tenant.TenantContext;
import com.openforge.connector.entity.ConnDefinition;
import com.openforge.connector.entity.ConnTriggerDlq;
import com.openforge.connector.mapper.ConnDefinitionMapper;
import com.openforge.connector.mapper.ConnTriggerDlqMapper;
import com.openforge.connector.spec.TriggerSpecs;
import com.openforge.connector.spi.ConnectorResult;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 触发执行分发（P2-2 §12.2）：EVENT/CRON 触发统一经此执行——失败即落 sys_connector_dlq
 * 应用级死信（对齐 B2 语义；broker %DLQ% 因幂等行先于业务插入实际不可达，见 AbstractEventConsumer）。
 * 调用方负责设置/清理 TenantContext（调度与消费线程跨租户逐连接器回填）。
 */
@Slf4j
@Service
public class TriggerDispatcher {

    private final ConnDefinitionMapper definitionMapper;
    private final ConnTriggerDlqMapper dlqMapper;
    private final ConnectorRuntime runtime;
    private final ObjectMapper objectMapper;

    public TriggerDispatcher(ConnDefinitionMapper definitionMapper,
                             ConnTriggerDlqMapper dlqMapper,
                             ConnectorRuntime runtime,
                             ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.dlqMapper = dlqMapper;
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    /**
     * 事件分发：跨租户取 EVENT 触发的已发布连接器，按 topic+tag 匹配逐个执行。
     * 事件 payload 覆盖静态 params（事件数据为权威输入）。返回命中执行数。
     */
    public int dispatchEvent(String topic, String eventType, Map<String, Object> payload, String eventId) {
        List<ConnDefinition> triggers = definitionMapper.selectPublishedByTriggerType(TriggerSpecs.TYPE_EVENT);
        int fired = 0;
        for (ConnDefinition def : triggers) {
            TriggerSpecs.TriggerConfig cfg = TriggerSpecs.parseRuntime(
                    def.getTriggerType(), def.getTriggerJson(), objectMapper);
            if (!TriggerSpecs.TYPE_EVENT.equals(cfg.type())
                    || !topic.equals(cfg.topic()) || !eventType.equals(cfg.tag())) {
                continue;
            }
            Map<String, Object> params = new HashMap<>(cfg.params());
            params.putAll(payload == null ? Map.of() : payload);
            TenantContext.setTenantId(def.getTenantId());
            MDC.put(com.openforge.common.trace.TraceIdFilter.MDC_KEY,
                    "evt-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                executeTrigger(def, params, TriggerSpecs.TYPE_EVENT, eventId);
                fired++;
            } finally {
                TenantContext.clear();
                MDC.clear();
            }
        }
        return fired;
    }

    /**
     * 单连接器触发执行：执行成功仅落执行日志（runtime 已写）；失败（含 BizException 调用错误
     * 与上游失败结果）落死信。状态非 PUBLISHED（调度间隙被停用）静默跳过。
     */
    public boolean executeTrigger(ConnDefinition def, Map<String, Object> params,
                                  String triggerType, String source) {
        if (!"PUBLISHED".equals(def.getStatus())) {
            log.debug("触发跳过（连接器非发布态）: connCode={}, status={}", def.getConnCode(), def.getStatus());
            return false;
        }
        try {
            ConnectorResult result = runtime.execute(def.getConnType(), def.getSpecJson(),
                    def.getCurrentVersion(), def.getId(), params, triggerType);
            if (result.success()) {
                return true;
            }
            recordDlq(def, triggerType, source, params, result.error());
            return false;
        } catch (BizException e) {
            recordDlq(def, triggerType, source, params, e.getMessage());
            return false;
        }
    }

    /** 死信落库（尽力而为：落库失败仅告警，不反噬触发线程）。 */
    private void recordDlq(ConnDefinition def, String triggerType, String source,
                           Map<String, Object> params, String error) {
        try {
            ConnTriggerDlq dlq = new ConnTriggerDlq();
            dlq.setTenantId(TenantContext.getTenantId());
            dlq.setConnId(def.getId());
            dlq.setConnCode(def.getConnCode());
            dlq.setConnVersion(def.getCurrentVersion());
            dlq.setTriggerType(triggerType);
            dlq.setSource(source == null ? null
                    : source.substring(0, Math.min(source.length(), 128)));
            dlq.setPayloadJson(objectMapper.writeValueAsString(params));
            dlq.setErrorMsg(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
            dlq.setRetryCount(0);
            dlq.setStatus("PENDING");
            dlqMapper.insert(dlq);
            log.warn("触发执行失败已落死信: connCode={}, trigger={}, source={} — {}",
                    def.getConnCode(), triggerType, source, error);
        } catch (Exception e) {
            log.error("死信落库失败: connCode={}", def.getConnCode(), e);
        }
    }

    /** 重放：原样重投 payload；成功置 RESOLVED，仍失败 retry_count+1 保持 PENDING。 */
    public Map<String, Object> replay(Long dlqId) {
        ConnTriggerDlq dlq = dlqMapper.selectById(dlqId);
        if (dlq == null) {
            throw new BizException(com.openforge.common.api.ErrorCode.INVALID_ARGUMENT, "死信记录不存在: " + dlqId);
        }
        ConnDefinition def = definitionMapper.selectById(dlq.getConnId());
        if (def == null) {
            throw new BizException(com.openforge.common.api.ErrorCode.CONN_NOT_FOUND,
                    "连接器已不存在: " + dlq.getConnCode());
        }
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(dlq.getPayloadJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new BizException(com.openforge.common.api.ErrorCode.CONN_SPEC_INVALID, "死信 payload 不可解析");
        }
        boolean success = executeTrigger(def, params, dlq.getTriggerType(), dlq.getSource());
        if (success) {
            dlq.setStatus("RESOLVED");
            dlq.setReplayedAt(LocalDateTime.now());
        } else {
            dlq.setRetryCount(dlq.getRetryCount() == null ? 1 : dlq.getRetryCount() + 1);
            // 重放失败错误以最新一次为准（成功失败本身已落执行日志）
        }
        dlqMapper.updateById(dlq);
        return Map.of("dlqId", dlqId, "connCode", dlq.getConnCode(),
                "status", success ? "SUCCESS" : "FAILED",
                "retryCount", dlq.getRetryCount() == null ? 0 : dlq.getRetryCount());
    }

    /** 死信分页（status 可选过滤；租户行级过滤自动生效）。 */
    public com.openforge.connector.dto.PageResponse<com.openforge.connector.dto.DlqResponse> dlqPage(
            String status, long page, long pageSize) {
        LambdaQueryWrapper<ConnTriggerDlq> wrapper = new LambdaQueryWrapper<ConnTriggerDlq>()
                .orderByDesc(ConnTriggerDlq::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ConnTriggerDlq::getStatus, status);
        }
        var result = dlqMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, Math.min(pageSize, 200)),
                wrapper);
        return com.openforge.connector.dto.PageResponse.from(result, com.openforge.connector.dto.DlqResponse::from);
    }

    /** 人工丢弃（保留记录供追溯，status=DISCARDED）。 */
    public void discard(Long dlqId) {
        ConnTriggerDlq dlq = dlqMapper.selectById(dlqId);
        if (dlq == null) {
            throw new BizException(com.openforge.common.api.ErrorCode.INVALID_ARGUMENT, "死信记录不存在: " + dlqId);
        }
        dlq.setStatus("DISCARDED");
        dlq.setReplayedAt(LocalDateTime.now());
        dlqMapper.updateById(dlq);
    }
}

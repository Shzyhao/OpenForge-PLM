package com.openforge.connector.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.connector.dto.InvokeResponse;
import com.openforge.connector.spec.ChainSpecs;
import com.openforge.connector.spec.TemplateRenderer;
import com.openforge.connector.spi.ConnectorResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多步骤链执行器（P3 刀1，集成编排器 MVP 设计 §14.4）：
 * 以 {@link ConnectorRuntime#executeCore} 为步骤原语（白名单/凭据/超时全部继承），
 * 只做"链面"：步骤顺序、入参组装（调用入参覆盖步骤静态 params + 上下文占位符渲染）、
 * 失败 fail-fast（continueOnError 可豁免）、汇总一条主日志（steps_json 摘要）。
 * 分支（branches）首刀建模未启用——引擎忽略，第二刀接 SpEL 求值。
 * 失败重放 = 整链重跑（sys_connector_dlq 既有通道；副作用幂等性由连接器作者保证）。
 */
@Slf4j
@Service
public class ChainExecutor {

    private final ConnectorRuntime runtime;
    private final ObjectMapper objectMapper;

    public ChainExecutor(ConnectorRuntime runtime, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.objectMapper = objectMapper;
    }

    public InvokeResponse execute(String specJson, int version, Long connId,
                                  Map<String, Object> params, String triggerType) {
        long start = System.currentTimeMillis();
        ChainSpecs.Chain chain = ChainSpecs.parse(jsonToMap(specJson), objectMapper, null);

        // steps 上下文视图：key -> {status, httpStatus, rowsReturned, body(解析后对象), error}
        Map<String, Object> stepsView = new LinkedHashMap<>();
        List<Map<String, Object>> summaries = new ArrayList<>();
        Map<String, ChainSpecs.StepDef> byKey = new LinkedHashMap<>();
        chain.steps().forEach(s -> byKey.put(s.key(), s));

        ConnectorResult lastSuccess = null;
        ConnectorResult firstFailure = null;
        String failedKey = null;
        // 图遍历（P3 刀4）：有 branches 的 from 步完成后按 SpEL 求值选路（expr 空 = 默认分支），
        // 无分支沿数组序；访问超 steps 数 ×2 判环路终止
        int cursor = 0;
        java.util.Set<String> skipped = new java.util.HashSet<>();
        int visits = 0;
        int maxVisits = Math.max(2, chain.steps().size() * 2);
        while (cursor >= 0 && cursor < chain.steps().size()) {
            ChainSpecs.StepDef step = chain.steps().get(cursor);
            if (++visits > maxVisits) {
                firstFailure = ConnectorResult.fail(null, "链执行路过深（疑似环路），已终止");
                failedKey = step.key();
                break;
            }
            if (skipped.contains(step.key())) {
                // 分支淘汰的支路：不执行，主线顺延
                cursor++;
                continue;
            }
            Map<String, Object> stepParams = mergeAndRender(step, params, stepsView);
            long stepStart = System.currentTimeMillis();
            ConnectorResult result;
            try {
                result = runtime.executeCore(step.type(), toJson(step.spec()), version, connId,
                        stepParams, triggerType);
            } catch (BizException e) {
                // 调用方错误（缺参/白名单/凭据缺失）转失败结果——链内不抛，统一走失败语义
                result = ConnectorResult.fail(null, e.getMessage());
            }
            stepsView.put(step.key(), stepView(result));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("key", step.key());
            summary.put("type", step.type());
            summary.put("status", result.success() ? "SUCCESS" : "FAILED");
            summary.put("durationMs", System.currentTimeMillis() - stepStart);
            if (result.error() != null) {
                summary.put("error", result.error());
            }
            summaries.add(summary);
            if (result.success()) {
                lastSuccess = result;
            } else if (firstFailure == null) {
                firstFailure = result;
                failedKey = step.key();
                if (!step.continueOnError()) {
                    break;
                }
            }
            // 分支选路（P3 刀4）：命中 → 跳到 to 并标记未命中的兄弟支路跳过；否则主线顺延
            String chosen = nextOf(chain, step, result.success(), stepsView, skipped);
            if (chosen != null) {
                cursor = indexOfKey(chain, chosen);
                continue;
            }
            cursor++;
        }
        // 整体结果：任一步失败 → FAILED（取首个失败步）；否则最后成功步（链的产物）
        ConnectorResult overall = firstFailure != null ? firstFailure
                : (lastSuccess != null ? lastSuccess : ConnectorResult.fail(null, "链无步骤执行"));

        String stepsJson;
        try {
            stepsJson = objectMapper.writeValueAsString(summaries);
        } catch (Exception e) {
            stepsJson = null;
        }
        boolean anyFailed = failedKey != null;
        long duration = System.currentTimeMillis() - start;
        runtime.writeChainLog(connId, version, triggerType,
                anyFailed ? "FAILED" : "SUCCESS",
                overall.httpStatus(), overall.rowsReturned(),
                anyFailed ? "步骤 " + failedKey + " 失败: " + overall.error() : null,
                duration, stepsJson);
        if (anyFailed) {
            log.warn("链执行失败: connId={}, 失败步={}, durationMs={}", connId, failedKey, duration);
        }
        return InvokeResponse.from(overall, duration);
    }

    private final com.openforge.common.spel.ExpressionEvaluator evaluator =
            new com.openforge.common.spel.ExpressionEvaluator();

    /**
     * 下一节点（P3 刀4）：from 命中 branches → 按规则序 SpEL 求值（#steps.x.y 上下文），
     * 首个 true 的 to 入选；expr 空的默认分支兜底。无分支规则 → 沿数组序下一步；末步 → null。
     * 步骤失败时不再选路（fail-fast 由上层 break；continueOnError 的失败步沿数组序走）。
     */
    private String nextOf(ChainSpecs.Chain chain, ChainSpecs.StepDef step, boolean success,
                          Map<String, Object> stepsView, java.util.Set<String> skipped) {
        List<Map<String, Object>> rules = chain.branches().stream()
                .filter(b -> step.key().equals(String.valueOf(b.get("from"))))
                .toList();
        if (!rules.isEmpty() && success) {
            Map<String, Object> variables = Map.of("steps", stepsView);
            String defaultTo = null;
            boolean matched = false;
            for (Map<String, Object> rule : rules) {
                String expr = rule.get("expr") == null ? "" : String.valueOf(rule.get("expr")).trim();
                if (expr.isEmpty()) {
                    defaultTo = String.valueOf(rule.get("to"));
                    continue;
                }
                try {
                    if (evaluator.evaluate(expr, variables)) {
                        markSiblingsSkipped(rules, String.valueOf(rule.get("to")), skipped);
                        return String.valueOf(rule.get("to"));
                    }
                } catch (IllegalArgumentException e) {
                    throw new BizException(com.openforge.common.api.ErrorCode.CONN_SPEC_INVALID, e.getMessage());
                }
            }
            if (defaultTo != null) {
                markSiblingsSkipped(rules, defaultTo, skipped);
                return defaultTo;
            }
            // 无命中且无默认 → 沿主线顺延（候选支路不标记）
        }
        return null;
    }

    private void markSiblingsSkipped(List<Map<String, Object>> rules, String chosenTo,
                                     java.util.Set<String> skipped) {
        for (Map<String, Object> rule : rules) {
            String to = String.valueOf(rule.get("to"));
            if (!to.equals(chosenTo)) {
                skipped.add(to);
            }
        }
    }

    private int indexOfKey(ChainSpecs.Chain chain, String key) {
        List<ChainSpecs.StepDef> steps = chain.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 步骤入参 = 步骤静态 params（低优先）被调用入参（高优先）覆盖，再渲染上下文占位符；
     * 并以 steps/params 键注入执行参数——步骤 spec 内 {{steps.x.y}} 占位符在 connector
     * 内部渲染（renderUrl/renderTemplate）时经同一点路径下钻取值（设计态豁免声明）。
     */
    private Map<String, Object> mergeAndRender(ChainSpecs.StepDef step, Map<String, Object> params,
                                               Map<String, Object> stepsView) {
        Map<String, Object> merged = new HashMap<>();
        merged.putAll(step.params());
        if (params != null) {
            merged.putAll(params);
        }
        Map<String, Object> renderContext = new HashMap<>(merged);
        renderContext.put("steps", stepsView);
        renderContext.put("params", params == null ? Map.of() : params);
        Map<String, Object> stepParams = TemplateRenderer.renderTemplate(merged, renderContext)
                instanceof Map<?, ?> rendered ? cast(rendered) : merged;
        // 上下文注入（豁免键）：供步骤 spec 内占位符在 connector 内部渲染时下钻
        stepParams.put("steps", stepsView);
        stepParams.put("params", params == null ? Map.of() : params);
        return stepParams;
    }

    /** 步骤输出上下文视图：body 为 JSON 对象/数组字符串时解析为树（点路径可下钻），否则保留原文。 */
    private Map<String, Object> stepView(ConnectorResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", result.success() ? "SUCCESS" : "FAILED");
        view.put("httpStatus", result.httpStatus());
        view.put("rowsReturned", result.rowsReturned());
        Object body = result.body();
        if (body instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    // 反序列化为原生 Map/List——点路径下钻 childOf 只认 Map
                    body = objectMapper.readValue(trimmed, Object.class);
                } catch (Exception ignored) {
                    // 非 JSON 保留原文
                }
            }
        }
        view.put("body", body);
        view.put("error", result.error());
        return view;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private Map<String, Object> jsonToMap(String json) {
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new BizException(com.openforge.common.api.ErrorCode.CONN_SPEC_INVALID, "链 spec 解析失败");
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(com.openforge.common.api.ErrorCode.CONN_SPEC_INVALID, "步骤 spec 序列化失败");
        }
    }
}

package com.openforge.connector.spec;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 触发配置规格（P2-2，集成编排器 MVP 设计 §12.2）：
 * - EVENT：{topic, tag, params?}——topic 须在平台既有主题白名单内（防拼错静默失效），
 *   事件 payload 覆盖静态 params（事件数据为权威输入）；
 * - CRON：{cron, params?}——Spring 6 段 cron（秒 分 时 日 月 周）；秒位禁用裸 *（防每秒风暴）。
 * 触发配置随发布进 conn_definition_version 快照，运行时按主档当前配置调度。
 */
public final class TriggerSpecs {

    public static final String TYPE_NONE = "NONE";
    public static final String TYPE_EVENT = "EVENT";
    public static final String TYPE_CRON = "CRON";
    private static final Set<String> TYPES = Set.of(TYPE_NONE, TYPE_EVENT, TYPE_CRON);

    /** 平台既有事件主题（一域一 topic，B2 设计）；可经配置扩展新模块主题。
     *  openforge-material 随 v1.16.0 material 事件化入列（part.released/bom.published）。 */
    public static final String DEFAULT_TOPICS =
            "openforge-meta,openforge-object,openforge-doc,openforge-change,openforge-task,openforge-connector,openforge-material";

    private TriggerSpecs() {
    }

    /** 解析产物：typed 配置（NONE 时各字段为 null/空 Map）。 */
    public record TriggerConfig(String type, String topic, String tag, String cron,
                                Map<String, Object> params) {

        public static TriggerConfig none() {
            return new TriggerConfig(TYPE_NONE, null, null, null, Map.of());
        }
    }

    /** 设计态校验 + 规范化（返回 canonical 配置 Map，落库 trigger_json）。 */
    public static Map<String, Object> validateAndNormalize(String triggerType, Map<String, Object> raw,
                                                           Set<String> allowedTopics) {
        if (triggerType == null || triggerType.isBlank() || TYPE_NONE.equals(triggerType)) {
            if (raw != null && !raw.isEmpty()) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "triggerType=NONE 时不应携带 trigger 配置");
            }
            return new LinkedHashMap<>();
        }
        if (!TYPES.contains(triggerType)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "triggerType 仅支持 NONE/EVENT/CRON: " + triggerType);
        }
        if (raw == null || raw.isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "triggerType=" + triggerType + " 需要对应 trigger 配置");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, Object> params = paramsOf(raw);
        if (TYPE_EVENT.equals(triggerType)) {
            String topic = str(raw.get("topic"));
            String tag = str(raw.get("tag"));
            if (topic.isEmpty()) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "EVENT 触发缺少 topic");
            }
            if (!allowedTopics.contains(topic)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "topic 不在平台既有主题白名单内: " + topic + "（允许: " + String.join(",", allowedTopics) + "）");
            }
            if (tag.isEmpty() || !tag.matches("^[a-z][a-zA-Z0-9._-]{0,63}$")) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "EVENT 触发 tag 须匹配 ^[a-z][a-zA-Z0-9._-]{0,63}$: " + tag);
            }
            normalized.put("topic", topic);
            normalized.put("tag", tag);
        } else {
            String cron = str(raw.get("cron"));
            if (cron.isEmpty()) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "CRON 触发缺少 cron 表达式");
            }
            if (cron.split("\\s+")[0].equals("*")) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "CRON 秒位不允许裸 *（防每秒触发风暴），至少 */n 或固定秒: " + cron);
            }
            try {
                org.springframework.scheduling.support.CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "cron 表达式非法（Spring 6 段：秒 分 时 日 月 周）: " + cron);
            }
            normalized.put("cron", cron);
        }
        if (!params.isEmpty()) {
            normalized.put("params", params);
        }
        return normalized;
    }

    /** 运行时容错解析（库内配置非法时降级为 NONE，不中断调度——配置错误在设计态已拦截）。 */
    public static TriggerConfig parseRuntime(String triggerType, String triggerJson,
                                             com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (triggerType == null || TYPE_NONE.equals(triggerType)) {
            return TriggerConfig.none();
        }
        try {
            Map<String, Object> raw = mapper.readValue(triggerJson == null ? "{}" : triggerJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return new TriggerConfig(triggerType, str(raw.get("topic")), str(raw.get("tag")),
                    str(raw.get("cron")), paramsOf(raw));
        } catch (Exception e) {
            return TriggerConfig.none();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(Map<String, Object> raw) {
        Object params = raw.get("params");
        if (params == null) {
            return Map.of();
        }
        if (!(params instanceof Map)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "trigger.params 须为对象");
        }
        return (Map<String, Object>) params;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

package com.openforge.connector.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多步骤链规格（P3 刀1，集成编排器 MVP 设计 §14.3）：spec_json schemaVersion=2。
 * steps 顺序执行（每步 spec 为 v1 单步字段形态），branches 首刀仅建模（引擎忽略）。
 * v1 单步在运行时适配为单步链，存量连接器零迁移。
 */
public final class ChainSpecs {

    public static final int SCHEMA_V2 = 2;
    /** 链步数上限（RC3：长链占用请求线程，每步超时沿用各步 spec timeoutMs） */
    public static final int MAX_STEPS = 10;

    /** 步骤定义（x/y 画布坐标随 spec 原样存储，引擎忽略）。 */
    public record StepDef(String key, String type, Map<String, Object> spec,
                          Map<String, Object> params, boolean continueOnError,
                          Integer x, Integer y) {
    }

    public record Chain(List<StepDef> steps, List<Map<String, Object>> branches) {
    }

    private ChainSpecs() {
    }

    /** spec Map 是否为 v2 链形态。 */
    public static boolean isChain(Map<String, Object> specMap) {
        return specMap != null
                && Integer.valueOf(SCHEMA_V2).equals(intOf(specMap.get("schemaVersion")));
    }

    /** spec JSON 是否为 v2 链形态（解析失败按非链处理，由单步路径承接报错）。 */
    public static boolean isChainJson(String specJson, ObjectMapper mapper) {
        if (specJson == null) {
            return false;
        }
        try {
            return isChain(mapper.readValue(specJson, new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 设计态校验 + 运行时解析共用。credChecker 非空时对每步凭据引用做存在性校验（设计态）；
     * 运行时传 null（省查询，缺失以 CONN_CRED_NOT_FOUND 在执行期承接）。
     */
    public static Chain parse(Map<String, Object> specMap, ObjectMapper mapper,
                              java.util.function.Consumer<String> credChecker) {
        if (!isChain(specMap)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "schemaVersion 须为 " + SCHEMA_V2 + "（链形态）");
        }
        Object stepsNode = specMap.get("steps");
        if (!(stepsNode instanceof List<?> rawSteps) || rawSteps.isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "链 spec 缺少 steps 或为空");
        }
        if (rawSteps.size() > MAX_STEPS) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "链步数超上限 " + MAX_STEPS + ": " + rawSteps.size());
        }
        Set<String> keys = new HashSet<>();
        List<StepDef> steps = new ArrayList<>();
        for (Object o : rawSteps) {
            if (!(o instanceof Map)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "steps 元素须为对象");
            }
            Map<String, Object> raw = cast(o);
            String key = str(raw.get("key"));
            if (!key.matches("^[a-z][a-z0-9_]{0,63}$")) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "步骤 key 须匹配 ^[a-z][a-z0-9_]{0,63}$: " + key);
            }
            if (!keys.add(key)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "步骤 key 重复: " + key);
            }
            String type = str(raw.get("type"));
            ConnectorSpecs.checkType(type);
            if (!(raw.get("spec") instanceof Map)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "步骤 " + key + " 缺少 spec 对象");
            }
            Map<String, Object> stepSpec = cast(raw.get("spec"));
            // 每步 spec 复用 v1 单步解析校验（设计态同时完成占位符闭包/超时/重试等约束）
            if (ConnectorSpecs.TYPE_HTTP_REST.equals(type)) {
                ConnectorSpecs.parseHttpRest(stepSpec, mapper, credExists(stepSpec, "credentialRef", credChecker));
            } else {
                ConnectorSpecs.parseJdbcReadonly(stepSpec, mapper, credExists(stepSpec, "passwordRef", credChecker));
            }
            Map<String, Object> params = raw.get("params") == null ? Map.of()
                    : requireMap(raw.get("params"), "步骤 " + key + " 的 params");
            steps.add(new StepDef(key, type, stepSpec, params,
                    Boolean.TRUE.equals(raw.get("continueOnError")),
                    intOrNull(raw.get("x")), intOrNull(raw.get("y"))));
        }
        List<Map<String, Object>> branches = parseBranches(specMap.get("branches"), keys);
        return new Chain(steps, branches);
    }

    /** 设计态 canonical 化（steps/branches 保序原样；后续字段引擎忽略则不保留）。 */
    public static Map<String, Object> normalize(Map<String, Object> specMap, ObjectMapper mapper,
                                                java.util.function.Consumer<String> credChecker) {
        Chain chain = parse(specMap, mapper, credChecker);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_V2);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StepDef step : chain.steps()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", step.key());
            s.put("type", step.type());
            s.put("spec", step.spec());
            if (!step.params().isEmpty()) {
                s.put("params", step.params());
            }
            if (step.continueOnError()) {
                s.put("continueOnError", true);
            }
            if (step.x() != null) {
                s.put("x", step.x());
            }
            if (step.y() != null) {
                s.put("y", step.y());
            }
            steps.add(s);
        }
        out.put("steps", steps);
        if (!chain.branches().isEmpty()) {
            out.put("branches", chain.branches());
        }
        return out;
    }

    private static List<Map<String, Object>> parseBranches(Object node, Set<String> keys) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> rawList)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "branches 须为数组");
        }
        List<Map<String, Object>> branches = new ArrayList<>();
        for (Object o : rawList) {
            if (!(o instanceof Map)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID, "branches 元素须为对象");
            }
            Map<String, Object> raw = cast(o);
            String from = str(raw.get("from"));
            String to = str(raw.get("to"));
            if (!keys.contains(from) || !keys.contains(to)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "分支端点须为已声明步骤 key: " + from + " -> " + to);
            }
            if (str(raw.get("expr")).isEmpty()) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "分支 expr 不能为空（空 expr 语义由默认分支承担）: " + from + " -> " + to);
            }
            branches.add(raw);
        }
        return branches;
    }

    private static boolean credExists(Map<String, Object> stepSpec, String refKey,
                                      java.util.function.Consumer<String> credChecker) {
        String ref = stepSpec.get(refKey) == null ? null : String.valueOf(stepSpec.get(refKey));
        if (ref == null) {
            return true;
        }
        if (credChecker != null) {
            credChecker.accept(ref);
        }
        // 运行时（checker 为空）：按存在处理，缺失在执行期以 CONN_CRED_NOT_FOUND 承接
        return true;
    }

    private static Map<String, Object> requireMap(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, what + " 须为对象");
        }
        return cast(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private static Integer intOf(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && s.matches("\\d+")) {
            return Integer.valueOf(s);
        }
        return null;
    }

    private static Integer intOrNull(Object value) {
        Integer v = intOf(value);
        return v;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

package com.openforge.connector.spec;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {{param}} 占位符渲染（集成编排器 MVP 设计 §4.1）：
 * - 树叶值恰为 "{{param}}" 时按参数原始类型替换（body 中的数字/布尔/对象不退化为字符串）；
 * - 字符串内嵌 "{{param}}" 时按文本替换；
 * - URL 场景走 urlEncode 变体（路径/查询段安全）。
 * 仅占位符替换、无表达式，堵死注入面。
 */
public final class TemplateRenderer {

    /** P3 刀1：允许点路径（{{steps.fetch.body.id}}），逐段下钻取值；单键形态不变。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_.]*)}}");

    private TemplateRenderer() {
    }

    /** 校验必填参数齐备（缺参在执行前失败，不发出半渲染请求）。 */
    public static void checkRequired(HttpRestSpec spec, Map<String, Object> params) {
        ConnectorSpecs.checkRequiredParams(spec.parameterSchema(), params);
    }

    /** 请求体模板渲染（类型保持替换）。 */
    public static Object renderTemplate(Object template, Map<String, Object> params) {
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), renderTemplate(e.getValue(), params));
            }
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(renderTemplate(v, params)));
            return out;
        }
        if (template instanceof String s) {
            return renderString(s, params, false);
        }
        return template;
    }

    public static String renderHeader(String value, Map<String, Object> params) {
        Object rendered = renderString(value, params, false);
        return String.valueOf(rendered);
    }

    /** URL 渲染：替换值做 URL 编码。 */
    public static String renderUrl(String url, Map<String, Object> params) {
        Object rendered = renderString(url, params, true);
        return String.valueOf(rendered);
    }

    private static Object renderString(String text, Map<String, Object> params, boolean urlEncode) {
        Matcher whole = PLACEHOLDER.matcher(text);
        // 整段即占位符 → 类型保持替换
        if (whole.matches()) {
            return valueOf(whole.group(1), params, urlEncode);
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = String.valueOf(valueOf(m.group(1), params, urlEncode));
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object valueOf(String name, Map<String, Object> params, boolean urlEncode) {
        Object value = resolvePath(name, params);
        if (value == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "占位符参数缺失: " + name);
        }
        if (urlEncode && !(value instanceof Number) && !(value instanceof Boolean)) {
            try {
                return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                return String.valueOf(value);
            }
        }
        return value;
    }

    /**
     * 点路径下钻：a.b.c 逐段取 Map；任一段缺失返回 null（调用方按缺参处理）。
     * 中间段为 JSON 字符串（如步骤 body 原文）时解析后继续下钻。
     */
    private static Object resolvePath(String path, Map<String, Object> params) {
        Object direct = params == null ? null : params.get(path);
        if (direct != null || (params != null && params.containsKey(path))) {
            return direct;
        }
        String[] segments = path.split("\\.");
        if (segments.length < 2 || params == null || !params.containsKey(segments[0])) {
            return null;
        }
        Object current = params.get(segments[0]);
        for (int i = 1; i < segments.length && current != null; i++) {
            current = childOf(current, segments[i]);
        }
        return current;
    }

    private static Object childOf(Object current, String key) {
        if (current instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (current instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.startsWith("{")) {
                try {
                    // 反序列化为 Map（原生类型值），后续段继续 Map 下钻
                    com.fasterxml.jackson.databind.ObjectMapper mapper =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                    Object parsed = mapper.readValue(trimmed, Object.class);
                    if (parsed instanceof Map<?, ?> parsedMap) {
                        return parsedMap.get(key);
                    }
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}

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

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)}}");

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
        if (params == null || !params.containsKey(name) || params.get(name) == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "占位符参数缺失: " + name);
        }
        Object value = params.get(name);
        if (urlEncode && !(value instanceof Number) && !(value instanceof Boolean)) {
            try {
                return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                return String.valueOf(value);
            }
        }
        return value;
    }
}

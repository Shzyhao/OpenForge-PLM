package com.openforge.connector.spec;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 模板渲染单测：类型保持替换 / 文本内嵌替换 / URL 编码 / 缺参失败。 */
class TemplateRendererTest {

    @Test
    @DisplayName("整段占位符按参数原始类型替换（数字/布尔/对象不退化为字符串）")
    void typedSubstitution() {
        Map<String, Object> params = new HashMap<>();
        params.put("qty", 3);
        params.put("active", true);
        params.put("nested", Map.of("a", 1));
        Object rendered = TemplateRenderer.renderTemplate(
                Map.of("qty", "{{qty}}", "active", "{{active}}", "nested", "{{nested}}", "note", "固定文本"),
                params);
        assertThat(rendered).isEqualTo(Map.of(
                "qty", 3, "active", true, "nested", Map.of("a", 1), "note", "固定文本"));
    }

    @Test
    @DisplayName("字符串内嵌占位符按文本替换")
    void inlineSubstitution() {
        assertThat(TemplateRenderer.renderTemplate("库存-{{code}}-尾部", Map.of("code", "M001")))
                .isEqualTo("库存-M001-尾部");
    }

    @Test
    @DisplayName("URL 渲染做 URL 编码")
    void urlEncode() {
        assertThat(TemplateRenderer.renderUrl("https://a.com/api/{{code}}/x", Map.of("code", "M 1")))
                .isEqualTo("https://a.com/api/M+1/x");
    }

    @Test
    @DisplayName("缺失参数抛 1000（执行前失败，不发半渲染请求）")
    void missingParamFails() {
        assertThatThrownBy(() -> TemplateRenderer.renderTemplate("{{missing}}", Map.of()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().getCode())
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("列表模板递归渲染")
    void listTemplate() {
        Object rendered = TemplateRenderer.renderTemplate(
                java.util.List.of("{{a}}", 1), Map.of("a", "x"));
        assertThat(rendered).isEqualTo(java.util.List.of("x", 1));
    }
}

package com.openforge.material.service;

import com.openforge.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttrTemplateServiceTest {

    private final AttrTemplateService service = new AttrTemplateService();

    private static final String TEMPLATE = """
            [{"key":"material","label":"材质","type":"string","required":true},
             {"key":"diameter","label":"外径","type":"number","required":false},
             {"key":"sealed","label":"是否密封","type":"boolean","required":false}]
            """;

    @Test
    @DisplayName("缺少必填属性拒绝")
    void missingRequiredShouldFail() {
        assertThatThrownBy(() -> service.validate(TEMPLATE, "{\"diameter\":120}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("material");
    }

    @Test
    @DisplayName("类型不符拒绝")
    void typeMismatchShouldFail() {
        assertThatThrownBy(() -> service.validate(TEMPLATE, "{\"material\":\"45#\",\"diameter\":\"120\"}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("diameter");
    }

    @Test
    @DisplayName("模板外的自定义键拒绝")
    void unknownKeyShouldFail() {
        assertThatThrownBy(() -> service.validate(TEMPLATE, "{\"material\":\"45#\",\"extra\":1}"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("extra");
    }

    @Test
    @DisplayName("合规属性通过；无模板分类跳过校验")
    void validPassesAndNoTemplateSkips() {
        assertThatCode(() -> service.validate(TEMPLATE, "{\"material\":\"45#\",\"diameter\":120,\"sealed\":true}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.validate(null, "{\"anything\":1}"))
                .doesNotThrowAnyException();
    }
}

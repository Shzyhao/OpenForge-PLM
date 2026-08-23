package com.openforge.material.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.material.entity.PartCategory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;

/**
 * 分类属性模板校验（开发文档 3.1.1：分类上挂属性模板）。
 * 模板格式：[{"key":"material","label":"材质","type":"string","required":true}, ...]
 * type 支持 string/number/boolean；required 缺失或类型不符拒绝。
 * 分类未配置模板时跳过校验（向后兼容）。
 */
@Service
public class AttrTemplateService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void validate(String templateJson, String attrsJson) {
        if (templateJson == null || templateJson.isBlank()) {
            return; // 无模板不校验
        }
        JsonNode template;
        JsonNode attrs;
        try {
            template = objectMapper.readTree(templateJson);
            attrs = (attrsJson == null || attrsJson.isBlank())
                    ? objectMapper.createObjectNode() : objectMapper.readTree(attrsJson);
        } catch (Exception e) {
            throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED, "属性或模板不是合法 JSON");
        }
        if (!template.isArray()) {
            throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED, "属性模板必须是数组");
        }

        for (JsonNode def : template) {
            String key = def.path("key").asText(null);
            if (key == null) {
                throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED, "模板字段缺少 key");
            }
            boolean required = def.path("required").asBoolean(false);
            String type = def.path("type").asText("string");
            JsonNode value = attrs.get(key);

            if (value == null || value.isNull()) {
                if (required) {
                    throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED,
                            "缺少必填属性: " + key + "(" + def.path("label").asText(key) + ")");
                }
                continue;
            }
            String mismatch = typeMismatch(type, value);
            if (mismatch != null) {
                throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED,
                        "属性 " + key + " 期望类型 " + type + " 但实际为 " + mismatch);
            }
        }
        // 不允许模板外的自定义键（保证数据受控；放开需配置开关）
        for (Iterator<Map.Entry<String, JsonNode>> it = attrs.fields(); it.hasNext(); ) {
            String key = it.next().getKey();
            boolean known = false;
            for (JsonNode def : template) {
                if (key.equals(def.path("key").asText())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new BizException(ErrorCode.ATTR_VALIDATION_FAILED, "属性 " + key + " 不在分类模板中");
            }
        }
    }

    private String typeMismatch(String expected, JsonNode value) {
        return switch (expected) {
            case "string" -> value.isTextual() ? null : "非文本";
            case "number" -> value.isNumber() ? null : "非数值";
            case "boolean" -> value.isBoolean() ? null : "非布尔";
            default -> null; // 未知类型放行（模板可扩展）
        };
    }
}

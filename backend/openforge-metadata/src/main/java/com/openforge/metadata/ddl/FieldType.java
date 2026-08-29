package com.openforge.metadata.ddl;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

/**
 * 元数据字段类型与物理列类型的映射（F2 设计 2）：
 * STRING→VARCHAR(max_length 默认 255)、NUMBER→NUMERIC(18,4)、DATE→TIMESTAMP、
 * BOOLEAN→SMALLINT、REFERENCE→BIGINT（值=被引对象记录 id）。
 */
public enum FieldType {

    STRING,
    NUMBER,
    DATE,
    BOOLEAN,
    REFERENCE;

    /** 物理列类型。maxLength 仅对 STRING 生效（1~4000，越界回退默认 255）。 */
    public String sqlType(Integer maxLength) {
        return switch (this) {
            case STRING -> "VARCHAR(" + stringWidth(maxLength) + ")";
            case NUMBER -> "NUMERIC(18,4)";
            case DATE -> "TIMESTAMP";
            case BOOLEAN -> "SMALLINT";
            case REFERENCE -> "BIGINT";
        };
    }

    private int stringWidth(Integer maxLength) {
        if (maxLength == null || maxLength < 1 || maxLength > 4000) {
            return 255;
        }
        return maxLength;
    }

    public static FieldType parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的字段类型: " + value + "（允许 STRING/NUMBER/DATE/BOOLEAN/REFERENCE）");
        }
    }
}

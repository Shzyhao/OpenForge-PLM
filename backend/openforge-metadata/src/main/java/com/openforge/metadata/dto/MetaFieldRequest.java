package com.openforge.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 字段定义请求（创建与更新共用）。 */
@Data
public class MetaFieldRequest {

    @NotBlank(message = "fieldKey 不能为空")
    private String fieldKey;

    @NotBlank(message = "displayName 不能为空")
    private String displayName;

    @NotBlank(message = "fieldType 不能为空")
    private String fieldType;

    /** 可选，默认 false */
    private Boolean required;

    /** 仅 STRING 生效，1~4000，默认 255 */
    private Integer maxLength;

    /** 仅 REFERENCE 必填：指向的 object_key */
    private String refObject;

    /** 仅 REFERENCE 可选：引用展示字段，默认 id */
    private String refField;
}

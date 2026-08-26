package com.openforge.metadata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 创建元对象（F2 设计 3：POST /api/v1/meta/objects）。 */
@Data
public class CreateMetaObjectRequest {

    @NotBlank(message = "objectKey 不能为空")
    private String objectKey;

    @NotBlank(message = "displayName 不能为空")
    private String displayName;

    @Valid
    @NotEmpty(message = "至少定义一个字段")
    private List<MetaFieldRequest> fields;
}

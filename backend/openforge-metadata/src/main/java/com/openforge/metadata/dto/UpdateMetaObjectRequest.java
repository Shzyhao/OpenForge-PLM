package com.openforge.metadata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 更新元对象（F2 设计 3：PUT /api/v1/meta/objects/{id}）。
 * 仅 DRAFT 可改；objectKey 不可变（表名与 API 路径由其派生）。
 */
@Data
public class UpdateMetaObjectRequest {

    @NotBlank(message = "displayName 不能为空")
    private String displayName;

    @Valid
    @NotEmpty(message = "至少定义一个字段")
    private List<MetaFieldRequest> fields;
}

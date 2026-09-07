package com.openforge.connector.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/** 连接器创建/更新请求（connCode 仅创建时生效，不可变）。 */
@Data
public class SaveConnRequest {

    private String connCode;

    @NotBlank(message = "connName 不能为空")
    private String connName;

    /** HTTP_REST / JDBC_READONLY（刀2） */
    @NotBlank(message = "connType 不能为空")
    private String connType;

    private String description;

    /** spec JSON 对象（schemaVersion=1，见集成编排器 MVP 设计 §4.1） */
    @NotNull(message = "spec 不能为空")
    private Map<String, Object> spec;
}

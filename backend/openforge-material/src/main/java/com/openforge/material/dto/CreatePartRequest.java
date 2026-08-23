package com.openforge.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePartRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String nameEn;

    @NotBlank
    @Pattern(regexp = "RAW|STANDARD|MADE|OUTSOURCED|SEMIFINISHED|PRODUCT",
            message = "物料类型必须为 RAW/STANDARD/MADE/OUTSOURCED/SEMIFINISHED/PRODUCT")
    private String type;

    @NotNull
    private Long categoryId;

    /** 动态属性 JSON 字符串（M2-2 起按分类属性模板校验） */
    private String attrs;

    @Size(max = 8)
    private String unit;
}

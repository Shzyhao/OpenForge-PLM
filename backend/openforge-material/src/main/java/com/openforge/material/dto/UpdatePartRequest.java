package com.openforge.material.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdatePartRequest {

    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String nameEn;

    @Size(max = 8)
    private String unit;

    /** 动态属性 JSON 字符串（整体替换） */
    private String attrs;
}

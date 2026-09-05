package com.openforge.material.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/** 新增替代件请求（仅草稿 BOM；优先级缺省追加组尾，系数缺省 1）。 */
@Data
public class SubstituteRequest {

    @NotNull
    private Long substitutePartId;

    @Positive
    private BigDecimal qtyCoefficient;

    private Integer priority;
}

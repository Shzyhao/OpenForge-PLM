package com.openforge.material.dto;

import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/** 调整替代件请求：优先级/系数至少一项。 */
@Data
public class SubstituteUpdateRequest {

    private Integer priority;

    @Positive
    private BigDecimal qtyCoefficient;
}

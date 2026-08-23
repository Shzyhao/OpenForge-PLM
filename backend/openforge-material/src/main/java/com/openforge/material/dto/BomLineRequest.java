package com.openforge.material.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BomLineRequest {

    @NotNull
    private Long childPartId;

    @NotNull
    @Positive
    private BigDecimal quantity;

    private String refDes;

    /** NORMAL/ALTERNATE/OPTIONAL */
    private String usageType;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;
}

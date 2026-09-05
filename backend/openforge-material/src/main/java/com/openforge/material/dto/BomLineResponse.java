package com.openforge.material.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** BOM 行视图：带子件信息、行号与替代组（GET /lines、expand 联动）。 */
@Data
public class BomLineResponse {

    private Long id;

    private Long bomId;

    /** 行号：同 BOM 内 1..n */
    private Integer position;

    private Long childPartId;

    private String childPartNumber;

    private String childPartName;

    private BigDecimal quantity;

    private String refDes;

    private String usageType;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String attrs;

    private List<SubstituteView> substitutes = new ArrayList<>();

    @Data
    public static class SubstituteView {
        private Long id;
        private Long substitutePartId;
        private String partNumber;
        private String name;
        private Integer priority;
        private BigDecimal qtyCoefficient;
    }
}

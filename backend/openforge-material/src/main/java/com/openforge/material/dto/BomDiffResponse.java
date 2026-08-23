package com.openforge.material.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 两 BOM 版本的行集合对比结果。 */
public record BomDiffResponse(Long bomIdA, Long bomIdB,
                              List<DiffEntry> added, List<DiffEntry> removed, List<DiffEntry> changed) {

    @Data
    public static class DiffEntry {
        /** ADDED / REMOVED / QUANTITY_CHANGED */
        private String type;
        private Long childPartId;
        private String childPartNumber;
        private String childPartName;
        private BigDecimal quantity;
        private BigDecimal oldQuantity;
    }
}

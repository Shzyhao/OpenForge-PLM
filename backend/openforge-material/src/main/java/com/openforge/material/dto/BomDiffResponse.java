package com.openforge.material.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 两 BOM 版本的行集合对比结果（行号对位排序；变更类型可多项并存）。 */
public record BomDiffResponse(Long bomIdA, Long bomIdB,
                              List<DiffEntry> added, List<DiffEntry> removed, List<DiffEntry> changed) {

    @Data
    public static class DiffEntry {
        /** 主变更类型（types 首项，兼容旧消费方）：ADDED/REMOVED/QUANTITY_CHANGED/REFDES_CHANGED/USAGE_TYPE_CHANGED/ATTR_CHANGED/SUBSTITUTE_CHANGED */
        private String type;
        /** 全部变更类型（一项行可有多种变更并存） */
        private List<String> types = new ArrayList<>();
        /** 行号（B 侧；REMOVED 取 A 侧） */
        private Integer position;
        private Long childPartId;
        private String childPartNumber;
        private String childPartName;
        private BigDecimal quantity;
        private BigDecimal oldQuantity;
        private String refDes;
        private String oldRefDes;
        private String usageType;
        private String oldUsageType;
        private String attrs;
        private String oldAttrs;
        /** 替代组快照（B 侧；REMOVED 取 A 侧），仅替代组变更时填充 */
        private List<SubstituteEntry> substitutes;
        private List<SubstituteEntry> oldSubstitutes;
    }

    @Data
    public static class SubstituteEntry {
        private Long substitutePartId;
        private String partNumber;
        private String name;
        private Integer priority;
        private BigDecimal qtyCoefficient;
    }
}

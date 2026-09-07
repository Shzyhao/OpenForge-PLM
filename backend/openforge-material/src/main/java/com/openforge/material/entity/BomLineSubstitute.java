package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** BOM 行替代项（替代件与主数据变更设计 §4.1，决策 D1：主件行的从表，不产生额外用量）。 */
@Data
@TableName("bom_line_substitute")
public class BomLineSubstitute {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bomLineId;

    private Long substitutePartId;

    /** 替代顺序，小者优先 */
    private Integer priority;

    /** 替代件用量 = 主件用量 × 系数 */
    private BigDecimal qtyCoefficient;

    /** 预留：替代条件（日期段/客户等），本期不定语义 */
    private String conditionJson;

    /** 最近一次生效变更单（追溯锚点） */
    private Long lastChangeId;

    private Long tenantId;

    private LocalDateTime createdAt;
}

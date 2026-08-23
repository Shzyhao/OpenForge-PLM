package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** BOM 行（开发文档 7.1）。 */
@Data
@TableName("bom_line")
public class BomLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bomId;

    private Long childPartId;

    private BigDecimal quantity;

    /** 位号，逗号分隔 */
    private String refDes;

    /** NORMAL/ALTERNATE/OPTIONAL */
    private String usageType;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String attrs;

    private Long tenantId;

    private LocalDateTime createdAt;
}

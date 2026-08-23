package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 物料主数据（开发文档 7.1）。 */
@Data
@TableName("part")
public class Part {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String partNumber;

    private String name;

    private String nameEn;

    private String type;

    private Long categoryId;

    /** 动态属性 JSON */
    private String attrs;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String unit;

    /** DRAFT/REVIEWING/RELEASED/FROZEN/PHASED_OUT */
    private String lifecycleState;

    private String version;

    private String securityLevel;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** BOM 头（开发文档 7.1；M2 为 EBOM 单类型）。 */
@Data
@TableName("bom")
public class Bom {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bomNumber;

    private Long parentPartId;

    private String bomType;

    private String version;

    /** DRAFT/REVIEWING/RELEASED */
    private String lifecycleState;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

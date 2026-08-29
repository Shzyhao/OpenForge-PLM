package com.openforge.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 元对象定义（F2 设计 2）。 */
@Data
@TableName("meta_object")
public class MetaObject {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** API 路径段与表名后缀: equipment */
    private String objectKey;

    private String displayName;

    /** dyn_equipment（dyn_ 前缀隔离自定义对象） */
    private String tableName;

    /** DRAFT/PUBLISHED */
    private String status;

    private Integer version;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;
}

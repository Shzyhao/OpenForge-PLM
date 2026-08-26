package com.openforge.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发布历史快照（F2 设计 2：definition + ddl 供回溯与 AI 溯源）。 */
@Data
@TableName("meta_object_version")
public class MetaObjectVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectId;

    /** 本次发布的版本号（= 发布时的 meta_object.version，发布成功后主表 version+1） */
    private Integer version;

    /** 发布时的完整定义 JSON 快照 */
    private String definition;

    /** 本次执行的 DDL 快照 */
    private String ddlText;

    private Long publishedBy;

    private LocalDateTime publishedAt;
}

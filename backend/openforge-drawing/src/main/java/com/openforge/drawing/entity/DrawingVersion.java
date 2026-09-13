package com.openforge.drawing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 图纸发布快照（RELEASED 时固化档案全字段 JSON；对齐 material PartVersion 语义）。 */
@Data
@TableName("drw_drawing_version")
public class DrawingVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long drawingId;

    /** 形如 A/0、A/1、B/0 */
    private String version;

    /** 发布时档案 + 文件清单 JSON */
    private String snapshot;

    private String state;

    private Long releasedBy;

    private Long tenantId;

    private LocalDateTime releasedAt;
}

package com.openforge.drawing.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 图纸档案（开发文档 CAD 规划的 M2 落地切面；版本语义对齐 material PartVersion）。 */
@Data
@TableName("drw_drawing")
public class DrawingInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String drawingNumber;

    private String title;

    /** 大版本 A/B/C（revise 递进） */
    private String versionMajor;

    /** 小版本（检入驱动） */
    private Integer versionMinor;

    /** DRAFT/REVIEWING/RELEASED/OBSOLETE */
    private String lifecycleState;

    /** 检出人（NULL=未检出） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long checkedOutBy;

    private LocalDateTime checkedOutAt;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public String version() {
        return versionMajor + "/" + versionMinor;
    }
}

package com.openforge.drawing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 图纸 ↔ 物料多对多关联（role：PART_DRAWING 零件图 / ASSEMBLY 装配图 / REFERENCE 参考）。 */
@Data
@TableName("drw_drawing_part")
public class DrawingPart {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long drawingId;

    private Long partId;

    /** 冗余物料编码快照（物料侧改名/删档不影响追溯） */
    private String partNumber;

    private String role;

    private Long tenantId;

    private LocalDateTime createdAt;
}

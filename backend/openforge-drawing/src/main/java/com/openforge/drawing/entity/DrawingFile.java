package com.openforge.drawing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 图纸文件（MAIN 主文件 / PREVIEW 预览副本 PDF·图片 / ATTACHMENT 附件）。 */
@Data
@TableName("drw_drawing_file")
public class DrawingFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long drawingId;

    private String fileName;

    private String storageKey;

    private Long fileSize;

    private String sha256;

    /** MAIN/PREVIEW/ATTACHMENT */
    private String kind;

    private Long tenantId;

    private LocalDateTime createdAt;
}

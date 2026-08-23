package com.openforge.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识条目（开发文档 7.5）。 */
@Data
@TableName("knowledge_item")
public class KnowledgeItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String content;

    private String summary;

    /** MANUAL/CHANGE_CASE/DOC */
    private String sourceType;

    private String sourceRef;

    /** 逗号分隔标签 */
    private String tags;

    private java.math.BigDecimal qualityScore;

    private Integer usageCount;

    private String status;

    /** 向量存储键 */
    private String vectorId;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;
}

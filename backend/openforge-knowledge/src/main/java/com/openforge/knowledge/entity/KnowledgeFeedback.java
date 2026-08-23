package com.openforge.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 使用反馈（自适应闭环数据源，开发文档 7.5）。 */
@Data
@TableName("knowledge_feedback")
public class KnowledgeFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String queryText;

    private Long itemId;

    /** CLICK/DISMISS/ADOPT/RATE */
    private String action;

    private Long userId;

    private LocalDateTime createdAt;
}

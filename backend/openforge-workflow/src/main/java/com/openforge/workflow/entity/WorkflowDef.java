package com.openforge.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_def")
public class WorkflowDef {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String defKey;

    private String name;

    private Integer version;

    /** DRAFT/PUBLISHED/RETIRED */
    private String status;

    /** 流程定义 JSON（见 engine.ProcessDefinition） */
    private String definition;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;
}

package com.openforge.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审批委托规则（v1.23）：principal 在生效期内把待办委托给 agent 办理。 */
@Data
@TableName("workflow_delegate")
public class WorkflowDelegate {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 委托人 */
    private Long principalId;

    /** 被委托人 */
    private Long agentId;

    /** 限定流程定义 key；NULL=全部流程 */
    private String defKey;

    private LocalDateTime startTime;

    /** NULL=长期有效 */
    private LocalDateTime endTime;

    private Integer enabled;

    private String remark;

    private LocalDateTime createdAt;
}

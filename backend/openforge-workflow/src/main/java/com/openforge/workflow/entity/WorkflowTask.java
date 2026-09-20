package com.openforge.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_task")
public class WorkflowTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long instanceId;

    private String nodeId;

    private String nodeName;

    /** 直接指派人（assignee.type=USER） */
    private Long assigneeId;

    /** 角色认领（assignee.type=ROLE） */
    private String candidateRole;

    /** APPROVE/REJECT（未完成为 NULL） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String action;

    private String comment;

    private LocalDateTime actedAt;

    private LocalDateTime createdAt;

    /** 委托代办时的原指派人（v1.23；act 覆盖 assignee_id 前记录，代办追溯用） */
    private Long delegatedFrom;

    /** 查询期标记：该任务经委托规则进入我的待办（不入库，随 JSON 下发前端打标） */
    @TableField(exist = false)
    private Boolean viaDelegation;

    public boolean isOpen() {
        return action == null;
    }
}

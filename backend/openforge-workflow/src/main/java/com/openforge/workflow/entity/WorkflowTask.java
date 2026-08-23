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

    public boolean isOpen() {
        return action == null;
    }
}

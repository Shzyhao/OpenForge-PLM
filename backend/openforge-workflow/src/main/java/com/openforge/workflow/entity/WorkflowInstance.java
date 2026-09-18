package com.openforge.workflow.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_instance")
public class WorkflowInstance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String defKey;

    private Integer defVersion;

    /** 启动时定义快照——在途实例不受新版本影响（架构文档 5.6 同理念） */
    private String defSnapshot;

    private String bizType;

    private Long bizId;

    /** 流程变量 JSON */
    private String variables;

    /** RUNNING/COMPLETED/REJECTED */
    private String state;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String currentNode;

    private Long initiatorId;

    /** 租户归属（R10）：实例全局表按 ID 寻址，读取以本字段校验同租户；GLOBAL_TABLES 免拦截器故服务层自守 */
    private Long tenantId;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;
}

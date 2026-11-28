package com.openforge.change.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** ECR 变更申请（开发文档 7.3；ECO/ECN 随 M4）。 */
@Data
@TableName("change_request")
public class ChangeRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ecrNumber;

    private String title;

    /** 联动幂等键（v1.31 P1-4）：drawing.released 联动单填 drawingNumber@version——
     * (tenant_id, link_key) 唯一约束兜底双通道/重投并发；人工 ECR 恒 NULL 不受约束（同名合法） */
    private String linkKey;

    private String reason;

    /** LOW/NORMAL/HIGH */
    private String urgency;

    /** 受影响对象引用 JSON */
    private String affectedItems;

    /** SUBMITTED/APPROVED/REJECTED */
    private String state;

    /** GENERIC/SUBSTITUTE_CHANGE/PART_STATE_CHANGE（刀2 类型化） */
    private String changeType;

    /** 类型化明细 JSON（含前后快照/影响清单） */
    private String payload;

    /** 审批后执行状态：PENDING/APPLIED/FAILED（GENERIC 为 null） */
    private String applyState;

    /** 执行结果 / 失败原因 */
    private String applyResult;

    private Long workflowInstanceId;

    private Long initiatorId;

    private Long tenantId;

    /** 软删标记（v1.25 回收站） */
    @com.baomidou.mybatisplus.annotation.TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
}

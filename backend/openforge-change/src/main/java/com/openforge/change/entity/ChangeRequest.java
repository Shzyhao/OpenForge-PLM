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

    private String reason;

    /** LOW/NORMAL/HIGH */
    private String urgency;

    /** 受影响对象引用 JSON */
    private String affectedItems;

    /** SUBMITTED/APPROVED/REJECTED */
    private String state;

    private Long workflowInstanceId;

    private Long initiatorId;

    private Long tenantId;

    private LocalDateTime createdAt;
}

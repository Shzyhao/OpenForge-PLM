package com.openforge.change.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** ECR 详情：含实时流程状态（关联 workflow 实例）。 */
@Data
public class EcrDetailResponse {
    private Long id;
    private String ecrNumber;
    private String title;
    private String reason;
    private String urgency;
    private String affectedItems;
    private String state;
    private String changeType;
    private String payload;
    private String applyState;
    private String applyResult;
    private Long workflowInstanceId;
    private LocalDateTime createdAt;

    /** 流程实时状态（流程服务不可用时为 null） */
    private String flowState;
    private String flowCurrentNode;
    /** 流程服务是否可达（v1.28 P2#12）：false 时 flowState=null 表示"服务不可达"而非"无流程"；null=未查询（非详情路径）。 */
    private Boolean flowReachable;
}

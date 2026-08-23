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
    private Long workflowInstanceId;
    private LocalDateTime createdAt;

    /** 流程实时状态（流程服务不可用时为 null） */
    private String flowState;
    private String flowCurrentNode;
}

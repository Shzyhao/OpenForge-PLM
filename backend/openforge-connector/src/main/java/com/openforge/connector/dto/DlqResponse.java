package com.openforge.connector.dto;

import com.openforge.connector.entity.ConnTriggerDlq;
import lombok.Data;

import java.time.LocalDateTime;

/** 死信记录视图（P2-2 §12.2）。payload 为触发入参原文（重放原样重投）。 */
@Data
public class DlqResponse {

    private Long id;
    private Long connId;
    private String connCode;
    private Integer connVersion;
    private String triggerType;
    private String source;
    private String payloadJson;
    private String errorMsg;
    private Integer retryCount;
    /** PENDING/RESOLVED/DISCARDED */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime replayedAt;

    public static DlqResponse from(ConnTriggerDlq dlq) {
        DlqResponse r = new DlqResponse();
        r.setId(dlq.getId());
        r.setConnId(dlq.getConnId());
        r.setConnCode(dlq.getConnCode());
        r.setConnVersion(dlq.getConnVersion());
        r.setTriggerType(dlq.getTriggerType());
        r.setSource(dlq.getSource());
        r.setPayloadJson(dlq.getPayloadJson());
        r.setErrorMsg(dlq.getErrorMsg());
        r.setRetryCount(dlq.getRetryCount());
        r.setStatus(dlq.getStatus());
        r.setCreatedAt(dlq.getCreatedAt());
        r.setReplayedAt(dlq.getReplayedAt());
        return r;
    }
}

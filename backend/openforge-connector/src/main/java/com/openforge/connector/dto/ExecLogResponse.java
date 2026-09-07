package com.openforge.connector.dto;

import com.openforge.connector.entity.ConnExecLog;
import lombok.Data;

import java.time.LocalDateTime;

/** 执行日志视图（已脱敏）。 */
@Data
public class ExecLogResponse {

    private Long id;
    private Long connId;
    private Integer connVersion;
    private String triggerType;
    private String status;
    private Integer httpStatus;
    private Integer rowsReturned;
    private Long durationMs;
    private String errorMsg;
    private String traceId;
    private LocalDateTime createdAt;

    public static ExecLogResponse from(ConnExecLog log) {
        ExecLogResponse response = new ExecLogResponse();
        response.setId(log.getId());
        response.setConnId(log.getConnId());
        response.setConnVersion(log.getConnVersion());
        response.setTriggerType(log.getTriggerType());
        response.setStatus(log.getStatus());
        response.setHttpStatus(log.getHttpStatus());
        response.setRowsReturned(log.getRowsReturned());
        response.setDurationMs(log.getDurationMs());
        response.setErrorMsg(log.getErrorMsg());
        response.setTraceId(log.getTraceId());
        response.setCreatedAt(log.getCreatedAt());
        return response;
    }
}

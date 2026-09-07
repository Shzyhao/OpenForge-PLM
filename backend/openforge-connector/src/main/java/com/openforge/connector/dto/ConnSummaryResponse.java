package com.openforge.connector.dto;

import com.openforge.connector.entity.ConnDefinition;
import lombok.Data;

import java.time.LocalDateTime;

/** 连接器摘要（列表页）。 */
@Data
public class ConnSummaryResponse {

    private Long id;
    private String connCode;
    private String connName;
    private String connType;
    private String status;
    private Integer currentVersion;
    private String description;
    /** 触发类型徽标（P2-2）：NONE/EVENT/CRON */
    private String triggerType;
    private LocalDateTime updatedAt;

    public static ConnSummaryResponse from(ConnDefinition def) {
        ConnSummaryResponse response = new ConnSummaryResponse();
        response.setId(def.getId());
        response.setConnCode(def.getConnCode());
        response.setConnName(def.getConnName());
        response.setConnType(def.getConnType());
        response.setStatus(def.getStatus());
        response.setCurrentVersion(def.getCurrentVersion());
        response.setDescription(def.getDescription());
        response.setTriggerType(def.getTriggerType() == null ? "NONE" : def.getTriggerType());
        response.setUpdatedAt(def.getUpdatedAt());
        return response;
    }
}

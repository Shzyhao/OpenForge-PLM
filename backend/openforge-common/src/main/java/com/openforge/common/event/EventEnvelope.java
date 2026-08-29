package com.openforge.common.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 事件信封（B2 设计 3.1）：所有业务事件统一结构，tags 承载事件类型。
 * eventId 为幂等去重键（消费侧 sys_event_consumed 去重）；tenantId/traceId 由生产侧
 * 自动填充（TenantContext/MDC），消费侧回填上下文——租户语义与链路串联跨过 MQ 不丢。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope {

    private String eventId;
    private String eventType;
    private int eventVersion;
    private String occurredAt;
    private String producer;
    private Long tenantId;
    private String traceId;
    private Map<String, Object> payload;
}

package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 触发执行死信（P2-2 §12.2）：EVENT/CRON 触发执行失败即落此表（应用级死信，
 * 不依赖 broker %DLQ%——B2 幂等行先于业务插入，重投会被判重 ACK，broker 死信实际不可达）。
 * status: PENDING 待重放 / RESOLVED 重放成功 / DISCARDED 人工丢弃。
 */
@Data
@TableName("sys_connector_dlq")
public class ConnTriggerDlq {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long connId;

    private String connCode;

    private Integer connVersion;

    /** EVENT / CRON（失败时的触发类型，重放沿用） */
    private String triggerType;

    /** EVENT: eventId；CRON: 触发时刻 ISO-8601 */
    private String source;

    /** 触发入参 canonical JSON（重放原样重投） */
    private String payloadJson;

    private String errorMsg;

    private Integer retryCount;

    /** PENDING/RESOLVED/DISCARDED */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime replayedAt;
}

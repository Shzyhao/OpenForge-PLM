package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 连接器执行日志（已脱敏；无界增长 → 保留期清理任务治理）。 */
@Data
@TableName("conn_exec_log")
public class ConnExecLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long connId;

    /** 0=设计态试运行（未发布版本） */
    private Integer connVersion;

    /** MANUAL / API */
    private String triggerType;

    /** SUCCESS/FAILED/TIMEOUT/BLOCKED */
    private String status;

    private Integer httpStatus;

    private Integer rowsReturned;

    private Long durationMs;

    /** 已脱敏（不含凭据/敏感头/响应体原文） */
    private String errorMsg;

    /** 链执行步骤摘要（P3 刀1：每步 key/status/duration/error 的 JSON 数组；单步为 null） */
    private String stepsJson;

    private String traceId;

    private LocalDateTime createdAt;
}

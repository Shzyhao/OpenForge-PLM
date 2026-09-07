package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 连接器定义主档（集成编排器 MVP 设计 §4）。运行时只按版本快照执行。 */
@Data
@TableName("conn_definition")
public class ConnDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String connCode;

    private String connName;

    /** HTTP_REST / JDBC_READONLY */
    private String connType;

    /** DRAFT/PUBLISHED/DISABLED */
    private String status;

    /** 0=从未发布 */
    private Integer currentVersion;

    private String description;

    /** 设计态 spec（集成编排器 MVP 设计 §4.1，schemaVersion=1） */
    private String specJson;

    /** 触发配置（P2-2 §12.2）：NONE/EVENT/CRON；配置体在 trigger_json */
    private String triggerType;

    private String triggerJson;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

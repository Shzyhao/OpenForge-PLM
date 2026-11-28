package com.openforge.connector.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 连接器发布版本快照（不可变；回滚 = 主档指回旧版本）。 */
@Data
@TableName("conn_definition_version")
public class ConnDefinitionVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long connId;

    private Integer version;

    private String specJson;

    /** 发布时的触发配置快照（P2-2，随 spec 一并不可变拷贝） */
    private String triggerType;

    private String triggerJson;

    private Long publishedBy;

    private LocalDateTime publishedAt;
}

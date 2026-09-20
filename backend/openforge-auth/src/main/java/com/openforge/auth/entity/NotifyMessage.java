package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内通知（v1.23）：双通道摄取（MQ 消费组 / 内部 HTTP），收件人维度收件箱。 */
@Data
@TableName("notify_message")
public class NotifyMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String eventType;

    private String bizType;

    private Long bizId;

    private String title;

    private String content;

    private Integer readFlag;

    private LocalDateTime createdAt;
}

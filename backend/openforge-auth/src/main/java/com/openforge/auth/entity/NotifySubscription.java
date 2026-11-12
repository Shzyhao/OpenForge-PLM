package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发布类事件订阅（v1.24）：同租户按 event_key fan-out。 */
@Data
@TableName("notify_subscription")
public class NotifySubscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private Long userId;

    private String eventKey;

    private LocalDateTime createdAt;
}

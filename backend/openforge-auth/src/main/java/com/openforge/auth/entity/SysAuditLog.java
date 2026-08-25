package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long operatorId;

    /** USER_CREATE/USER_DISABLE/USER_RESET_PASSWORD/ROLE_ASSIGN/PERM_BIND/... */
    private String action;

    private String targetType;

    private String targetId;

    private String detail;

    private LocalDateTime createdAt;
}

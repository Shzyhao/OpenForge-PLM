package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表。审计字段规范见开发文档第 7 章（tenant_id 预留多租户）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String displayName;

    private String email;

    /** ACTIVE / DISABLED */
    private String status;

    private Long tenantId;

    /** 所属组织（sys_org.id），注册时为空，由管理员分配；ALWAYS 使"移出组织"(置null)生效 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long orgId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private Long updatedBy;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

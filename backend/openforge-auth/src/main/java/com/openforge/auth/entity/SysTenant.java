package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户主档（架构文档 7.3：共享库 + tenant_id 行级隔离）。 */
@Data
@TableName("sys_tenant")
public class SysTenant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantCode;

    private String tenantName;

    /** 0/1，停用租户禁止登录 */
    private Integer enabled;

    private String remark;

    private LocalDateTime createdAt;
}

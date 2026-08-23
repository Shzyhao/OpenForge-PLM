package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_org")
public class SysOrg {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orgCode;

    private String orgName;

    private Long parentId;

    /** 物化路径，如 /1/5/12/；后代查询用 path LIKE 'prefix%' */
    private String path;

    private Integer sortOrder;

    private String status;

    private Long tenantId;

    private LocalDateTime createdAt;
}

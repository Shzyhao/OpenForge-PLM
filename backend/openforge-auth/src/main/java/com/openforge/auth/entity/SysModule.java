package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模块注册表（A4 设计 3.2）。 */
@Data
@TableName("sys_module")
public class SysModule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String moduleKey;

    /** KERNEL/BUSINESS/AI/EXTENSION */
    private String moduleType;

    private String displayName;

    private String version;

    /** ENABLED/DISABLED/BROKEN */
    private String status;

    /** JSON 数组字符串 */
    private String routes;

    private String menu;

    private String dependencies;

    private String flywayTable;

    private String healthPath;

    /** EXTENSION 型指向 meta_object.id */
    private Long ownerRef;

    /** 服务直连地址（网关动态路由目标，如 http://localhost:8082） */
    private String serviceUri;

    private LocalDateTime registeredAt;

    private LocalDateTime heartbeatAt;
}

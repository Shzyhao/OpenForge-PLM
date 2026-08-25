package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** 1 成功 / 0 失败 */
    private Integer success;

    /** OK/BAD_CREDENTIALS/LOCKED/DISABLED */
    private String reason;

    private String ip;

    private String userAgent;

    private LocalDateTime createdAt;
}

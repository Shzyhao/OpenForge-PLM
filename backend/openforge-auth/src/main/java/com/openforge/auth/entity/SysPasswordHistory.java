package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_password_history")
public class SysPasswordHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String passwordHash;

    private LocalDateTime createdAt;
}

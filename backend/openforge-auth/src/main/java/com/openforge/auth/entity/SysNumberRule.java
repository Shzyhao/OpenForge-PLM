package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_number_rule")
public class SysNumberRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleKey;

    private String ruleName;

    /** 段定义 JSON: [{"type":"CONST","value":"P"},{"type":"DATE","pattern":"yyyyMMdd"},{"type":"SEQ","length":5}] */
    private String segments;

    /** NONE / DAILY / MONTHLY / YEARLY */
    private String resetPolicy;

    private String status;

    private Long tenantId;

    private LocalDateTime createdAt;
}

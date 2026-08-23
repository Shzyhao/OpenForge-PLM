package com.openforge.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 复合主键行锁计数器：(rule_key, period) 唯一，FOR UPDATE 事务内自增防重号。 */
@Data
@TableName("sys_number_counter")
public class SysNumberCounter {

    private String ruleKey;

    private String period;

    private Long currentValue;
}

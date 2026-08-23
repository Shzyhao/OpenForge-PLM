package com.openforge.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.auth.entity.SysNumberCounter;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface NumberCounterMapper extends BaseMapper<SysNumberCounter> {

    /** 行锁读取（事务内）：与后续 UPDATE 配合，全程持锁保证并发取号唯一。行不存在返回 null。 */
    @Select("SELECT rule_key, period, current_value FROM sys_number_counter "
            + "WHERE rule_key = #{ruleKey} AND period = #{period} FOR UPDATE")
    SysNumberCounter selectForUpdate(@Param("ruleKey") String ruleKey, @Param("period") String period);

    @Update("UPDATE sys_number_counter SET current_value = #{value} "
            + "WHERE rule_key = #{ruleKey} AND period = #{period}")
    int updateValue(@Param("ruleKey") String ruleKey, @Param("period") String period,
                    @Param("value") long value);
}

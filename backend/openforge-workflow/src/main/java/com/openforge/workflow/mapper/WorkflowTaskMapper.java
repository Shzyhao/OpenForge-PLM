package com.openforge.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.workflow.entity.WorkflowTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkflowTaskMapper extends BaseMapper<WorkflowTask> {

    /**
     * 任务行悲观锁（v1.33 修正 v1.31 P0-2 的实现缺陷）：锁行并返回最新实体。
     * 必须用本方法而非「lockById 实例 + selectById 重读任务」——MyBatis 一级缓存
     * 会让同事务内第二次 selectById 命中旧快照（锁内重读失效，PG/H2 均实测双推进）；
     * 本语句是独立 statement 穿透缓存，FOR UPDATE 语义由 H2LockProbe 实证有效。
     */
    @Select("SELECT * FROM workflow_task WHERE id = #{id} FOR UPDATE")
    WorkflowTask lockById(@Param("id") Long id);
}

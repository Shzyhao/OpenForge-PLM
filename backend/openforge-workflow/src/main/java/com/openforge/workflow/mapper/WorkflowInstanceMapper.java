package com.openforge.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.workflow.entity.WorkflowInstance;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WorkflowInstanceMapper extends BaseMapper<WorkflowInstance> {

    /**
     * 实例行悲观锁（v1.31 P0-2）：同实例的任务办理在 act() 入口串行化——
     * 防同任务双击双推进、ANY 或签 cancelOpenTasks 互覆/行锁互等死锁、ALL 会签末两票并发双等待挂死。
     * H2/PG 均支持 FOR UPDATE（测试上下文为 H2）。
     */
    @Select("SELECT id FROM workflow_instance WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);
}

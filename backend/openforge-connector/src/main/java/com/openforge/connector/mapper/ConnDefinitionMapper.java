package com.openforge.connector.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.connector.entity.ConnDefinition;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ConnDefinitionMapper extends BaseMapper<ConnDefinition> {

    /**
     * 跨租户查询已发布且带触发的连接器（P2-2）：调度/消费线程无租户上下文，
     * 绕过租户行级过滤取全量，再按行 tenant_id 逐个回填上下文执行。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id, conn_code, conn_type, current_version, spec_json, trigger_type, trigger_json, status "
            + "FROM conn_definition WHERE deleted = 0 AND status = 'PUBLISHED' "
            + "AND trigger_type = #{triggerType}")
    List<ConnDefinition> selectPublishedByTriggerType(@Param("triggerType") String triggerType);
}

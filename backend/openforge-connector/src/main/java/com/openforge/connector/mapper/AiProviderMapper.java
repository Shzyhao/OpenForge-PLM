package com.openforge.connector.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.connector.entity.AiProvider;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiProviderMapper extends BaseMapper<AiProvider> {

    /** 密钥轮换批查（R1）：跨租户游标分页（密钥列仅轮换路径需要）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, provider_code, provider_name, base_url, api_key_enc, model, timeout_ms, "
            + "enabled, priority, tenant_id FROM ai_provider WHERE deleted = 0 AND id > #{cursor} "
            + "ORDER BY id LIMIT #{limit}")
    List<AiProvider> selectBatchForRotation(@Param("cursor") Long cursor, @Param("limit") int limit);
}

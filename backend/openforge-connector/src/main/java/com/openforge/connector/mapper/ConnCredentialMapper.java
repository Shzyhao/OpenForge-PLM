package com.openforge.connector.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.connector.entity.ConnCredential;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ConnCredentialMapper extends BaseMapper<ConnCredential> {

    /** 密钥轮换批查（R1）：跨租户游标分页——主密钥是部署级资产，与租户无关。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, cred_code, cred_name, auth_type, secret_cipher, extra_json, tenant_id "
            + "FROM conn_credential WHERE deleted = 0 AND id > #{cursor} ORDER BY id LIMIT #{limit}")
    List<ConnCredential> selectBatchForRotation(@Param("cursor") Long cursor, @Param("limit") int limit);
}

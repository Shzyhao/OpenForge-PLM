package com.openforge.change.mapper;

import com.openforge.change.entity.ChangeRequest;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 变更单回收站原生 SQL（v1.25 设计 §4）：绕过 @TableLogic 显式读写软删行；租户由
 * TenantLineInnerInterceptor 统一改写。SUBMITTED（审批在途）不可删/不可 purge——
 * workflow_instance 悬挂引用护栏在 EcrService。
 */
public interface ChangeRecycleMapper {

    @Select("SELECT * FROM change_request WHERE deleted = 1 ORDER BY id DESC")
    List<ChangeRequest> trashedRequests();

    @Select("SELECT * FROM change_request WHERE id = #{id} AND deleted = 1")
    ChangeRequest trashedRequest(@Param("id") Long id);

    @Update("UPDATE change_request SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreRequest(@Param("id") Long id);

    @Delete("DELETE FROM change_request WHERE id = #{id} AND deleted = 1")
    int purgeRequest(@Param("id") Long id);
}

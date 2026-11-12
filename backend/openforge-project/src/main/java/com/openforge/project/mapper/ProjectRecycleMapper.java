package com.openforge.project.mapper;

import com.openforge.project.entity.Project;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 项目回收站原生 SQL（v1.25 设计 §4）：绕过 @TableLogic 显式读写软删行；租户由
 * TenantLineInnerInterceptor 统一改写。purge 连带 project_task GC。
 */
public interface ProjectRecycleMapper {

    @Select("SELECT * FROM project WHERE deleted = 1 ORDER BY id DESC")
    List<Project> trashedProjects();

    @Select("SELECT * FROM project WHERE id = #{id} AND deleted = 1")
    Project trashedProject(@Param("id") Long id);

    @Update("UPDATE project SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreProject(@Param("id") Long id);

    @Delete("DELETE FROM project_task WHERE project_id = #{projectId}")
    int deleteTasksByProject(@Param("projectId") Long projectId);

    @Delete("DELETE FROM project WHERE id = #{id} AND deleted = 1")
    int purgeProject(@Param("id") Long id);
}

package com.openforge.material.mapper;

import com.openforge.material.entity.Part;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 物料回收站原生 SQL（v1.23 设计 §3；命名带域前缀——mono 单 classpath 下两域同名 mapper 会 bean 冲突，实纱②）：绕过 @TableLogic 自动过滤显式读写软删行。
 * 租户过滤由 TenantLineInnerInterceptor 统一改写追加（starter-data 全局装配）。
 */
public interface PartRecycleMapper {

    @Select("SELECT * FROM part WHERE deleted = 1 ORDER BY id DESC")
    List<Part> trashedParts();

    @Select("SELECT * FROM part WHERE id = #{id} AND deleted = 1")
    Part trashedPart(@Param("id") Long id);

    /** 恢复冲突检查：编码是否已被在册记录占用（uk_part_number 全行唯一） */
    @Select("SELECT COUNT(*) FROM part WHERE part_number = #{partNumber} AND deleted = 0")
    int liveCountByNumber(@Param("partNumber") String partNumber);

    @Update("UPDATE part SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restorePart(@Param("id") Long id);
}

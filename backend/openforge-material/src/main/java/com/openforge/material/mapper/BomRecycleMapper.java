package com.openforge.material.mapper;

import com.openforge.material.entity.Bom;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * BOM 回收站与彻底删除原生 SQL（v1.24 设计 §2）：绕过 @TableLogic 显式读写软删行。
 * 租户过滤由 TenantLineInnerInterceptor 统一改写追加；命名带域前缀防 mono 同名 bean 冲突。
 */
public interface BomRecycleMapper {

    @Select("SELECT * FROM bom WHERE deleted = 1 ORDER BY id DESC")
    List<Bom> trashedBoms();

    @Select("SELECT * FROM bom WHERE id = #{id} AND deleted = 1")
    Bom trashedBom(@Param("id") Long id);

    /** 恢复冲突检查（uk_bom_number 全行唯一） */
    @Select("SELECT COUNT(*) FROM bom WHERE bom_number = #{bomNumber} AND deleted = 0")
    int liveCountByNumber(@Param("bomNumber") String bomNumber);

    @Update("UPDATE bom SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreBom(@Param("id") Long id);

    // ===== 彻底删除（v1.24）：物料引用护栏 + 行级 GC =====

    /** 物料被 BOM 行引用计数（含软删 BOM——彻底删除物料要求先清所有引用） */
    @Select("SELECT COUNT(*) FROM bom_line WHERE child_part_id = #{partId}")
    int bomLineRefCount(@Param("partId") Long partId);

    /** 物料被替代组引用计数 */
    @Select("SELECT COUNT(*) FROM bom_line_substitute WHERE substitute_part_id = #{partId}")
    int substituteRefCount(@Param("partId") Long partId);

    @Delete("DELETE FROM bom_line_substitute WHERE bom_line_id IN (SELECT id FROM bom_line WHERE bom_id = #{bomId})")
    int deleteSubstitutesByBom(@Param("bomId") Long bomId);

    @Delete("DELETE FROM bom_line WHERE bom_id = #{bomId}")
    int deleteLinesByBom(@Param("bomId") Long bomId);

    @Delete("DELETE FROM bom WHERE id = #{id} AND deleted = 1")
    int purgeBom(@Param("id") Long id);
}

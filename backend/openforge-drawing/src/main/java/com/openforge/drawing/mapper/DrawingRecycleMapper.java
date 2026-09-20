package com.openforge.drawing.mapper;

import com.openforge.drawing.entity.DrawingInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 图纸回收站原生 SQL（v1.23 设计 §3；命名带域前缀——mono 单 classpath 下两域同名 mapper 会 bean 冲突，实纱②）：绕过 @TableLogic 自动过滤显式读写软删行。
 * 租户过滤由 TenantLineInnerInterceptor 统一改写追加（starter-data 全局装配）。
 */
public interface DrawingRecycleMapper {

    @Select("SELECT * FROM drw_drawing WHERE deleted = 1 ORDER BY id DESC")
    List<DrawingInfo> trashedDrawings();

    @Select("SELECT * FROM drw_drawing WHERE id = #{id} AND deleted = 1")
    DrawingInfo trashedDrawing(@Param("id") Long id);

    /** 恢复冲突检查：图号是否已被在册记录占用（uk_drw_number 全行唯一） */
    @Select("SELECT COUNT(*) FROM drw_drawing WHERE drawing_number = #{drawingNumber} AND deleted = 0")
    int liveCountByNumber(@Param("drawingNumber") String drawingNumber);

    @Update("UPDATE drw_drawing SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreDrawing(@Param("id") Long id);
}

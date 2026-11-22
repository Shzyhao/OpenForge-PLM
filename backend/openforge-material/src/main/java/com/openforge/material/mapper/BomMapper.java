package com.openforge.material.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openforge.material.entity.Bom;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface BomMapper extends BaseMapper<Bom> {

    /**
     * BOM 行悲观锁（v1.31 P1-5）：revise 升版入口串行化——防并发升版双算同版本号
     * （串行后按链尾重算 nextVersion，见 BomService.revise）。锁顺序单一无互等。
     */
    @Select("SELECT id FROM bom WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);

    /** 同物料同类型 BOM 链尾（id 最大=最近升版），供 revise 重算下一版本号。 */
    @Select("SELECT version FROM bom WHERE parent_part_id = #{parentPartId} AND bom_type = #{bomType} "
            + "AND tenant_id = #{tenantId} ORDER BY id DESC LIMIT 1")
    String latestVersion(@Param("parentPartId") Long parentPartId, @Param("bomType") String bomType,
                          @Param("tenantId") Long tenantId);
}

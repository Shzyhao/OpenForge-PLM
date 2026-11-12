package com.openforge.knowledge.mapper;

import com.openforge.knowledge.entity.KnowledgeItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 知识回收站原生 SQL（v1.25 设计 §4）：绕过 @TableLogic 显式读写软删行；租户由
 * TenantLineInnerInterceptor 统一改写。域前缀命名防 mono 同名 bean 冲突（实纱②）。
 */
public interface KnowledgeRecycleMapper {

    @Select("SELECT * FROM knowledge_item WHERE deleted = 1 ORDER BY id DESC")
    List<KnowledgeItem> trashedItems();

    @Select("SELECT * FROM knowledge_item WHERE id = #{id} AND deleted = 1")
    KnowledgeItem trashedItem(@Param("id") Long id);

    @Update("UPDATE knowledge_item SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreItem(@Param("id") Long id);

    /** 反馈 GC（purge 前清从表，knowledge_feedback.feedback_id 引用 item） */
    @Delete("DELETE FROM knowledge_feedback WHERE item_id = #{itemId}")
    int deleteFeedbackByItem(@Param("itemId") Long itemId);

    @Delete("DELETE FROM knowledge_item WHERE id = #{id} AND deleted = 1")
    int purgeItem(@Param("id") Long id);
}

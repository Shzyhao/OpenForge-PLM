package com.openforge.doc.mapper;

import com.openforge.doc.entity.DocFile;
import com.openforge.doc.entity.DocInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文档回收站与彻底删除原生 SQL（v1.24 设计 §2/§3）：绕过 @TableLogic 显式读写软删行；
 * 租户过滤由 TenantLineInnerInterceptor 统一改写追加。
 */
public interface DocRecycleMapper {

    @Select("SELECT * FROM doc_info WHERE deleted = 1 ORDER BY id DESC")
    List<DocInfo> trashedDocs();

    @Select("SELECT * FROM doc_info WHERE id = #{id} AND deleted = 1")
    DocInfo trashedDoc(@Param("id") Long id);

    /** 恢复冲突检查（uk_doc_number 全行唯一） */
    @Select("SELECT COUNT(*) FROM doc_info WHERE doc_number = #{docNumber} AND deleted = 0")
    int liveCountByNumber(@Param("docNumber") String docNumber);

    @Update("UPDATE doc_info SET deleted = 0 WHERE id = #{id} AND deleted = 1")
    int restoreDoc(@Param("id") Long id);

    // ===== 彻底删除（v1.24）：文件键读取 + 行 GC（文件字节由 StorageClient.delete 承接）=====

    @Select("SELECT * FROM doc_file WHERE doc_info_id = #{docId}")
    List<DocFile> filesOf(@Param("docId") Long docId);

    @Delete("DELETE FROM doc_file WHERE doc_info_id = #{docId}")
    int deleteFilesByDoc(@Param("docId") Long docId);

    @Delete("DELETE FROM doc_info WHERE id = #{id} AND deleted = 1")
    int purgeDoc(@Param("id") Long id);
}

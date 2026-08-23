package com.openforge.doc.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doc_info")
public class DocInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String docNumber;

    private String title;

    private String docType;

    private String versionMajor;

    private Integer versionMinor;

    /** DRAFT/REVIEWING/RELEASED */
    private String lifecycleState;

    /** 检出人（NULL=未检出） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long checkedOutBy;

    private LocalDateTime checkedOutAt;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public String version() {
        return versionMajor + "/" + versionMinor;
    }
}

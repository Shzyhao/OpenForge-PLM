package com.openforge.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 表单/列表布局（F3-2 设计器制品：字段顺序/可见性/标签/列宽/跨列）。 */
@Data
@TableName("meta_form_layout")
public class MetaFormLayout {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectId;

    /** FORM / LIST */
    private String layoutType;

    /** JSON: {fields:[{fieldKey,visible,label,width,colSpan}]} */
    private String layout;

    private Long updatedBy;

    private LocalDateTime updatedAt;
}

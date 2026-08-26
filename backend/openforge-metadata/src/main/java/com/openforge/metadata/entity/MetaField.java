package com.openforge.metadata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 元对象字段定义（F2 设计 2）。 */
@Data
@TableName("meta_field")
public class MetaField {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long objectId;

    /** 列名（snake_case，白名单校验） */
    private String fieldKey;

    private String displayName;

    /** STRING/NUMBER/DATE/BOOLEAN/REFERENCE */
    private String fieldType;

    /** 0/1 */
    private Integer required;

    /** STRING 列宽 */
    private Integer maxLength;

    /** REFERENCE 指向的 object_key */
    private String refObject;

    /** 引用展示字段（默认 id） */
    private String refField;

    private Integer sortOrder;
}

package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("part_category")
public class PartCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String categoryCode;

    private String categoryName;

    private Long parentId;

    /** 物化路径 /1/5/ */
    private String path;

    /** 属性模板 JSON（M2-2 启用） */
    private String attrTemplate;

    private Integer sortOrder;

    private Long tenantId;

    private LocalDateTime createdAt;
}

package com.openforge.material.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 物料版本快照（发布时固化）。 */
@Data
@TableName("part_version")
public class PartVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long partId;

    private String version;

    /** 发布时刻全字段 JSON */
    private String snapshot;

    private String state;

    private Long releasedBy;

    private LocalDateTime releasedAt;
}

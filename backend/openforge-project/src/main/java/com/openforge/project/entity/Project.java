package com.openforge.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectNumber;

    private String name;

    private String description;

    private Long ownerId;

    /** ACTIVE/CLOSED */
    private String status;

    private LocalDate plannedStart;

    private LocalDate plannedEnd;

    private Long tenantId;

    private Long createdBy;

    private LocalDateTime createdAt;
}

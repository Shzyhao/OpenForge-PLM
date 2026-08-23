package com.openforge.project.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("project_task")
public class ProjectTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String title;

    /** TODO/DOING/DONE */
    private String status;

    private Long assigneeId;

    private LocalDate dueDate;

    private Long tenantId;

    private LocalDateTime createdAt;
}

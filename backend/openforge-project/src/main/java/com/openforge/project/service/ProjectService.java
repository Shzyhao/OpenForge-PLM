package com.openforge.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.project.client.NumberClient;
import com.openforge.project.dto.PageResponse;
import com.openforge.project.entity.Project;
import com.openforge.project.entity.ProjectTask;
import com.openforge.project.mapper.ProjectMapper;
import com.openforge.project.mapper.ProjectTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 项目与任务（开发文档 3.4 M6 简化版）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    static final String NUMBER_RULE_KEY = "project";

    private final ProjectMapper projectMapper;
    private final ProjectTaskMapper taskMapper;
    private final NumberClient numberClient;

    @Transactional
    public Project create(String name, String description, Long ownerId,
                          java.time.LocalDate plannedStart, java.time.LocalDate plannedEnd, Long operatorId) {
        Project project = new Project();
        project.setProjectNumber(numberClient.next(NUMBER_RULE_KEY));
        project.setName(name);
        project.setDescription(description);
        project.setOwnerId(ownerId);
        project.setStatus("ACTIVE");
        project.setPlannedStart(plannedStart);
        project.setPlannedEnd(plannedEnd);
        project.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        project.setCreatedBy(operatorId);
        projectMapper.insert(project);
        return project;
    }

    public Project require(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "项目不存在");
        }
        return project;
    }

    public PageResponse<Project> page(long page, long pageSize) {
        Page<Project> result = projectMapper.selectPage(
                Page.of(page, Math.min(pageSize, 100)),
                new LambdaQueryWrapper<Project>().orderByDesc(Project::getId));
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 结项：仅进行中项目可结项。 */
    @Transactional
    public Project close(Long id) {
        Project project = require(id);
        if (!"ACTIVE".equals(project.getStatus())) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "项目已结项");
        }
        project.setStatus("CLOSED");
        projectMapper.updateById(project);
        return project;
    }

    public ProjectTask addTask(Long projectId, String title, Long assigneeId, java.time.LocalDate dueDate) {
        require(projectId);
        ProjectTask task = new ProjectTask();
        task.setProjectId(projectId);
        task.setTitle(title);
        task.setStatus("TODO");
        task.setAssigneeId(assigneeId);
        task.setDueDate(dueDate);
        task.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        taskMapper.insert(task);
        return task;
    }

    public List<ProjectTask> tasks(Long projectId) {
        require(projectId);
        return taskMapper.selectList(new LambdaQueryWrapper<ProjectTask>()
                .eq(ProjectTask::getProjectId, projectId).orderByAsc(ProjectTask::getId));
    }

    /** 任务状态流转：TODO→DOING→DONE。 */
    public ProjectTask moveTask(Long taskId, String target) {
        ProjectTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在");
        }
        boolean valid = switch (task.getStatus()) {
            case "TODO" -> "DOING".equals(target);
            case "DOING" -> "DONE".equals(target);
            default -> false;
        };
        if (!valid) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION,
                    "任务状态只能 TODO→DOING→DONE，当前 " + task.getStatus() + " → " + target + " 不合法");
        }
        task.setStatus(target);
        taskMapper.updateById(task);
        return task;
    }

    /** 项目维度任务统计（报表）。 */
    public Map<String, Long> taskStats(Long projectId) {
        return tasks(projectId).stream()
                .collect(Collectors.groupingBy(ProjectTask::getStatus, Collectors.counting()));
    }
}

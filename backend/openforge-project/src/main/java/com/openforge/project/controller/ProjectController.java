package com.openforge.project.controller;

import com.openforge.common.annotation.RequirePermission;
import com.openforge.common.api.ApiResponse;
import com.openforge.project.dto.PageResponse;
import com.openforge.project.entity.Project;
import com.openforge.project.entity.ProjectTask;
import com.openforge.project.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    @RequirePermission("project:manage")
    public ApiResponse<Project> create(@RequestBody CreateProjectRequest request,
                                       HttpServletRequest httpRequest) {
        return ApiResponse.ok(projectService.create(request.getName(), request.getDescription(),
                request.getOwnerId(), request.getPlannedStart(), request.getPlannedEnd(),
                currentUserId(httpRequest)));
    }

    @GetMapping
    public ApiResponse<PageResponse<Project>> page(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return ApiResponse.ok(projectService.page(page, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<Project> detail(@PathVariable Long id) {
        return ApiResponse.ok(projectService.require(id));
    }

    @PostMapping("/{id}/close")
    @RequirePermission("project:manage")
    public ApiResponse<Project> close(@PathVariable Long id) {
        return ApiResponse.ok(projectService.close(id));
    }

    @PostMapping("/{id}/tasks")
    @RequirePermission("project:manage")
    public ApiResponse<ProjectTask> addTask(@PathVariable Long id,
                                            @RequestBody CreateTaskRequest request) {
        return ApiResponse.ok(projectService.addTask(id, request.getTitle(),
                request.getAssigneeId(), request.getDueDate()));
    }

    @GetMapping("/{id}/tasks")
    public ApiResponse<List<ProjectTask>> tasks(@PathVariable Long id) {
        return ApiResponse.ok(projectService.tasks(id));
    }

    @PostMapping("/tasks/{taskId}/move")
    public ApiResponse<ProjectTask> moveTask(@PathVariable Long taskId,
                                             @RequestBody Map<String, String> body) {
        return ApiResponse.ok(projectService.moveTask(taskId, body.get("status")));
    }

    @GetMapping("/{id}/task-stats")
    public ApiResponse<Map<String, Long>> taskStats(@PathVariable Long id) {
        return ApiResponse.ok(projectService.taskStats(id));
    }

    private Long currentUserId(HttpServletRequest request) {
        String header = request.getHeader("X-User-Id");
        if (header == null) {
            return null;
        }
        try {
            return Long.valueOf(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    public static class CreateProjectRequest {
        @NotBlank
        private String name;
        private String description;
        private Long ownerId;
        private LocalDate plannedStart;
        private LocalDate plannedEnd;
    }

    @Data
    public static class CreateTaskRequest {
        @NotBlank
        private String title;
        private Long assigneeId;
        private LocalDate dueDate;
    }
}

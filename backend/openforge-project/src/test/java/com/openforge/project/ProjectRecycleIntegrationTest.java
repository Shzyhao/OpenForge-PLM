package com.openforge.project;

import com.openforge.common.api.BizException;
import com.openforge.project.client.NumberClient;
import com.openforge.project.entity.Project;
import com.openforge.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 项目回收站集成（v1.25 设计 §4，H2）：软删→回收站→恢复→purge 连带任务 GC。
 */
@SpringBootTest
class ProjectRecycleIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private NumberClient numberClient;

    private static final AtomicLong seq = new AtomicLong(900);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("project")).thenAnswer(i -> "PRJ" + String.format("%010d", seq.incrementAndGet()));
    }

    @Test
    @DisplayName("删除→回收站→恢复往返；purge 连带任务 GC 且行物理消失")
    void deleteRestorePurge() {
        Project p = projectService.create("回收站项目", "desc", 1L, null, null, 1L);
        projectService.addTask(p.getId(), "任务一", 1L, null);

        projectService.delete(p.getId());
        assertThat(projectService.trashed()).anyMatch(t -> t.getId().equals(p.getId()));
        assertThatThrownBy(() -> projectService.require(p.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("项目不存在");

        Project restored = projectService.restore(p.getId());
        assertThat(restored.getId()).isEqualTo(p.getId());
        assertThat(projectService.trashed()).noneMatch(t -> t.getId().equals(p.getId()));

        // purge 面：再删→purge→项目与任务行均物理消失（tasks() 走 require 会按 404 拒，故直查表）
        projectService.delete(p.getId());
        projectService.purge(p.getId());
        assertThat(projectService.trashed()).noneMatch(t -> t.getId().equals(p.getId()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project WHERE id = ?", Long.class, p.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_task WHERE project_id = ?", Long.class, p.getId())).isZero();
        assertThatThrownBy(() -> projectService.restore(p.getId()))
                .isInstanceOf(BizException.class).hasMessageContaining("回收站中不存在该项目");
    }
}

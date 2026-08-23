package com.openforge.project;

import com.openforge.common.api.BizException;
import com.openforge.project.client.NumberClient;
import com.openforge.project.entity.Project;
import com.openforge.project.entity.ProjectTask;
import com.openforge.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 项目域集成：建项目取号、任务状态机 TODO→DOING→DONE、结项约束。 */
@SpringBootTest
class ProjectIntegrationTest {

    @Autowired
    private ProjectService projectService;

    @MockBean
    private NumberClient numberClient;

    private static final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("project")).thenAnswer(i -> "PRJ" + String.format("%010d", seq.incrementAndGet()));
    }

    @Test
    @DisplayName("建项目自动取号，任务状态机推进与统计正确，结项后不可重复结项")
    void projectLifecycle() {
        Project project = projectService.create("新型密封件研发", "耐温260℃新配方", 7L, null, null, 1L);
        assertThat(project.getProjectNumber()).startsWith("PRJ");
        assertThat(project.getStatus()).isEqualTo("ACTIVE");

        ProjectTask t1 = projectService.addTask(project.getId(), "材料选型", 7L, null);
        ProjectTask t2 = projectService.addTask(project.getId(), "样件试制", 8L, null);
        projectService.addTask(project.getId(), "可靠性验证", 9L, null);

        // 非法流转拒绝
        assertThatThrownBy(() -> projectService.moveTask(t1.getId(), "DONE"))
                .isInstanceOf(BizException.class);

        projectService.moveTask(t1.getId(), "DOING");
        projectService.moveTask(t1.getId(), "DONE");
        projectService.moveTask(t2.getId(), "DOING");

        assertThat(projectService.taskStats(project.getId()))
                .containsEntry("DONE", 1L)
                .containsEntry("DOING", 1L)
                .containsEntry("TODO", 1L);

        // 结项与重复结项
        assertThat(projectService.close(project.getId()).getStatus()).isEqualTo("CLOSED");
        assertThatThrownBy(() -> projectService.close(project.getId()))
                .isInstanceOf(BizException.class);
    }
}

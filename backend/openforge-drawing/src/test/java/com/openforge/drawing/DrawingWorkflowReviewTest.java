package com.openforge.drawing;

import com.openforge.drawing.client.AuthAuditClient;
import com.openforge.drawing.client.DrawingWorkflowClient;
import com.openforge.drawing.client.NumberClient;
import com.openforge.drawing.entity.DrawingInfo;
import com.openforge.drawing.service.DrawingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图纸审签接流程引擎（v1.25 设计 §6，H2，flag-on）：submit 启流程实例；detail 惰性回流
 * 终态（COMPLETED→发布 / REJECTED→打回 DRAFT）——ECR 同款拉模型。
 */
@SpringBootTest
@TestPropertySource(properties = "openforge.drawing.workflow-review=true")
class DrawingWorkflowReviewTest {

    @Autowired
    private DrawingService drawingService;

    @MockBean
    private NumberClient numberClient;

    @MockBean
    private AuthAuditClient auditClient;

    @MockBean
    private DrawingWorkflowClient workflowClient;

    private static final AtomicLong drwSeq = new AtomicLong(900);

    @BeforeEach
    void stubNumbers() {
        when(numberClient.next("drawing")).thenAnswer(i -> "DW" + String.format("%010d", drwSeq.incrementAndGet()));
    }

    private byte[] content(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private DrawingInfo submittedDrawing() throws Exception {
        DrawingInfo d = drawingService.create("审签流程图纸", 9L);
        drawingService.uploadFile(d.getId(), "main.txt",
                new ByteArrayInputStream(content("wf-review")), "MAIN");
        return drawingService.submit(d.getId());
    }

    @Test
    @DisplayName("flag-on 时 submit 启动 drawing-review 流程实例")
    void submitStartsWorkflow() throws Exception {
        DrawingInfo d = submittedDrawing();
        assertThat(d.getLifecycleState()).isEqualTo("REVIEWING");
        verify(workflowClient).startReview(eq("drawing-review"), eq(d.getId()));
    }

    @Test
    @DisplayName("惰性回流：实例 COMPLETED→detail 触发发布（RELEASED）；REJECTED→打回 DRAFT")
    void lazySyncTerminalStates() throws Exception {
        DrawingInfo d = submittedDrawing();
        when(workflowClient.findByBiz(d.getId()))
                .thenReturn(new DrawingWorkflowClient.InstanceView(99L, "drawing-review", "COMPLETED", null));
        DrawingInfo synced = drawingService.detail(d.getId());
        assertThat(synced.getLifecycleState()).isEqualTo("RELEASED");

        DrawingInfo d2 = submittedDrawing();
        when(workflowClient.findByBiz(d2.getId()))
                .thenReturn(new DrawingWorkflowClient.InstanceView(100L, "drawing-review", "REJECTED", null));
        DrawingInfo synced2 = drawingService.detail(d2.getId());
        assertThat(synced2.getLifecycleState()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("实例仍在途（RUNNING）→ detail 不改变状态")
    void runningInstanceNoop() throws Exception {
        DrawingInfo d = submittedDrawing();
        when(workflowClient.findByBiz(d.getId()))
                .thenReturn(new DrawingWorkflowClient.InstanceView(101L, "drawing-review", "RUNNING", "review"));
        assertThat(drawingService.detail(d.getId()).getLifecycleState()).isEqualTo("REVIEWING");
    }
}

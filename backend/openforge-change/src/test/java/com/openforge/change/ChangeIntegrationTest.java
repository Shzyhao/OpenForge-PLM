package com.openforge.change;

import com.openforge.change.client.NumberClient;
import com.openforge.change.client.WorkflowClient;
import com.openforge.change.dto.EcrDetailResponse;
import com.openforge.change.dto.EcrRequest;
import com.openforge.change.entity.ChangeRequest;
import com.openforge.change.service.EcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ECR 集成：创建自动取号 + 绑定流程实例 + 详情实时关联流程状态。 */
@SpringBootTest
class ChangeIntegrationTest {

    @Autowired
    private EcrService ecrService;

    @MockBean
    private NumberClient numberClient;
    @MockBean
    private WorkflowClient workflowClient;

    private static final AtomicLong ecrSeq = new AtomicLong(100);
    private static final AtomicLong flowSeq = new AtomicLong(500);

    @BeforeEach
    void stubClients() {
        when(numberClient.next("ecr")).thenAnswer(i -> "ECR" + String.format("%010d", ecrSeq.incrementAndGet()));
        when(workflowClient.start(eq("ecr-review"), eq("ECR"), org.mockito.ArgumentMatchers.anyLong(), anyMap()))
                .thenAnswer(i -> flowSeq.incrementAndGet());
    }

    @Test
    @DisplayName("创建 ECR：取号 + 启动 ecr-review 流程并绑定实例 ID")
    void createBindsWorkflow() {
        EcrRequest request = new EcrRequest();
        request.setTitle("法兰盘材质变更");
        request.setReason("客户要求提升耐温等级");
        request.setUrgency("HIGH");

        ChangeRequest ecr = ecrService.create(request, 7L);

        assertThat(ecr.getEcrNumber()).startsWith("ECR");
        assertThat(ecr.getState()).isEqualTo("SUBMITTED");
        assertThat(ecr.getWorkflowInstanceId()).isNotNull();
        verify(workflowClient).start(eq("ecr-review"), eq("ECR"), eq(ecr.getId()), anyMap());
    }

    @Test
    @DisplayName("详情：实时关联流程状态；流程服务不可用时降级为空")
    void detailJoinsFlowState() {
        EcrRequest request = new EcrRequest();
        request.setTitle("降级测试变更");
        ChangeRequest ecr = ecrService.create(request, 7L);

        // 流程在途
        when(workflowClient.findByBiz("ECR", ecr.getId()))
                .thenReturn(new WorkflowClient.InstanceView(ecr.getWorkflowInstanceId(), "ecr-review", "RUNNING", "review"));
        EcrDetailResponse detail = ecrService.detail(ecr.getId());
        assertThat(detail.getFlowState()).isEqualTo("RUNNING");
        assertThat(detail.getFlowCurrentNode()).isEqualTo("review");

        // 流程服务不可用 → 降级
        when(workflowClient.findByBiz("ECR", ecr.getId())).thenReturn(null);
        EcrDetailResponse degraded = ecrService.detail(ecr.getId());
        assertThat(degraded.getFlowState()).isNull();
        assertThat(degraded.getEcrNumber()).isNotNull();
    }
}

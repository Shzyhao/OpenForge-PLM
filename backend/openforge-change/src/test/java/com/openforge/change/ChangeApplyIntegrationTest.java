package com.openforge.change;

import com.openforge.change.client.MaterialClient;
import com.openforge.change.client.NumberClient;
import com.openforge.change.client.WorkflowClient;
import com.openforge.change.dto.EcrDetailResponse;
import com.openforge.change.dto.EcrRequest;
import com.openforge.change.entity.ChangeRequest;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.change.service.EcrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刀2 统一变更中心集成：类型化创建（服务端权威快照/影响清单）、审批回流即执行（D6）、失败重试。
 */
@SpringBootTest
class ChangeApplyIntegrationTest {

    @Autowired
    private EcrService ecrService;

    @MockBean
    private NumberClient numberClient;
    @MockBean
    private WorkflowClient workflowClient;
    @MockBean
    private MaterialClient materialClient;

    private static final AtomicLong ecrSeq = new AtomicLong(200);
    private static final AtomicLong flowSeq = new AtomicLong(600);

    @BeforeEach
    void stubClients() {
        when(numberClient.next("ecr")).thenAnswer(i -> "ECR" + String.format("%010d", ecrSeq.incrementAndGet()));
        when(workflowClient.start(eq("ecr-review"), eq("ECR"), anyLong(), anyMap()))
                .thenAnswer(i -> flowSeq.incrementAndGet());
    }

    private EcrRequest request(String type, String payload) {
        EcrRequest r = new EcrRequest();
        r.setTitle("测试变更-" + type);
        r.setChangeType(type);
        r.setPayload(payload);
        return r;
    }

    @Test
    @DisplayName("替代变更创建：校验发布版 BOM，before 以服务端替代组为准")
    void substituteCreateBuildsAuthoritativeSnapshot() {
        when(materialClient.bom(1L)).thenReturn(new MaterialClient.BomView(1L, "B001", "A/1", "RELEASED", 10L));
        when(materialClient.line(11L)).thenReturn(new MaterialClient.LineView(11L, 1L, 1, 20L, "P20", "主件"));
        when(materialClient.substitutes(1L, 11L)).thenReturn(List.of(
                new MaterialClient.SubstituteInput(30L, 1, BigDecimal.ONE)));
        when(materialClient.part(30L)).thenReturn(new MaterialClient.PartView(30L, "P30", "存量替代件", "RELEASED"));
        when(materialClient.part(31L)).thenReturn(new MaterialClient.PartView(31L, "P31", "新增替代件", "RELEASED"));
        when(materialClient.part(32L)).thenReturn(new MaterialClient.PartView(32L, "P32", "新增替代件2", "RELEASED"));

        String payload = """
                {"bomId":1,"lineId":11,
                 "after":[{"substitutePartId":31,"priority":1,"qtyCoefficient":2},
                          {"substitutePartId":32}]}
                """;
        ChangeRequest ecr = ecrService.create(request("SUBSTITUTE_CHANGE", payload), 7L);

        assertThat(ecr.getApplyState()).isEqualTo("PENDING");
        assertThat(ecr.getPayload()).contains("\"before\"").contains("P20").contains("\"after\"");
        // 权威 before：服务端返回的 30 号替代件必须入快照，前端未提交任何 before
        assertThat(ecr.getPayload()).contains("30");
    }

    @Test
    @DisplayName("替代变更创建：草稿 BOM 拒绝；行不属于该 BOM 拒绝")
    void substituteCreateValidation() {
        when(materialClient.bom(2L)).thenReturn(new MaterialClient.BomView(2L, "B002", "A/1", "DRAFT", 10L));
        assertThatThrownBy(() -> ecrService.create(request("SUBSTITUTE_CHANGE",
                "{\"bomId\":2,\"lineId\":11,\"after\":[]}"), 7L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));

        when(materialClient.bom(1L)).thenReturn(new MaterialClient.BomView(1L, "B001", "A/1", "RELEASED", 10L));
        when(materialClient.line(11L)).thenReturn(new MaterialClient.LineView(11L, 99L, 1, 20L, "P20", "主件"));
        assertThatThrownBy(() -> ecrService.create(request("SUBSTITUTE_CHANGE",
                "{\"bomId\":1,\"lineId\":11,\"after\":[]}"), 7L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("禁用变更创建：状态前置校验 + where-used 影响清单入 payload")
    void partStateCreateBuildsImpactSnapshot() {
        when(materialClient.part(40L)).thenReturn(new MaterialClient.PartView(40L, "P40", "禁用料", "RELEASED"));
        when(materialClient.whereUsed(40L)).thenReturn(List.of(
                Map.of("bomNumber", "B001", "usageRole", "MAIN", "parentPartNumber", "P10")));

        ChangeRequest ecr = ecrService.create(request("PART_STATE_CHANGE",
                "{\"partId\":40,\"targetState\":\"FROZEN\"}"), 7L);

        assertThat(ecr.getApplyState()).isEqualTo("PENDING");
        assertThat(ecr.getPayload()).contains("FROZEN").contains("RELEASED").contains("B001");

        // 非法目标状态 / 状态不允许
        assertThatThrownBy(() -> ecrService.create(request("PART_STATE_CHANGE",
                "{\"partId\":40,\"targetState\":\"PHASED_OUT\"}"), 7L))
                .isInstanceOf(BizException.class);
        when(materialClient.part(41L)).thenReturn(new MaterialClient.PartView(41L, "P41", "草稿件", "DRAFT"));
        assertThatThrownBy(() -> ecrService.create(request("PART_STATE_CHANGE",
                "{\"partId\":41,\"targetState\":\"FROZEN\"}"), 7L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("审批通过即执行（D6）：替代变更 apply 成功 APPLIED；失败 FAILED 可重试")
    void applyOnApprovalAndRetry() {
        when(materialClient.bom(1L)).thenReturn(new MaterialClient.BomView(1L, "B001", "A/1", "RELEASED", 10L));
        when(materialClient.line(11L)).thenReturn(new MaterialClient.LineView(11L, 1L, 1, 20L, "P20", "主件"));
        when(materialClient.substitutes(1L, 11L)).thenReturn(List.of());
        when(materialClient.part(31L)).thenReturn(new MaterialClient.PartView(31L, "P31", "新增替代件", "RELEASED"));
        ChangeRequest ecr = ecrService.create(request("SUBSTITUTE_CHANGE",
                "{\"bomId\":1,\"lineId\":11,\"after\":[{\"substitutePartId\":31,\"priority\":1}]}"), 7L);

        // 第一次执行失败（物料端拒绝）
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.INTERNAL_ERROR, "物料服务调用失败: 替代件构成装配逻辑环"))
                .when(materialClient).applySubstitutes(eq(11L), anyList(), eq(ecr.getId()));
        when(workflowClient.findByBiz("ECR", ecr.getId())).thenReturn(
                new WorkflowClient.InstanceView(ecr.getWorkflowInstanceId(), "ecr-review", "COMPLETED", "end"));
        EcrDetailResponse failed = ecrService.detail(ecr.getId());
        assertThat(failed.getState()).isEqualTo("APPROVED");
        assertThat(failed.getApplyState()).isEqualTo("FAILED");
        assertThat(failed.getApplyResult()).contains("装配逻辑环");

        // 重试成功
        org.mockito.Mockito.doAnswer(i -> null)
                .when(materialClient).applySubstitutes(eq(11L), anyList(), eq(ecr.getId()));
        ChangeRequest retried = ecrService.retryApply(ecr.getId());
        assertThat(retried.getApplyState()).isEqualTo("APPLIED");
        assertThat(retried.getApplyResult()).contains("替代组已应用");

        // 已成功后重试被拒
        assertThatThrownBy(() -> ecrService.retryApply(ecr.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("禁用变更审批通过执行：applyPartLifecycle 被调；SUBMITTED 不执行")
    void partStateApplyAndNoPrematureApply() {
        when(materialClient.part(40L)).thenReturn(new MaterialClient.PartView(40L, "P40", "禁用料", "RELEASED"));
        when(materialClient.whereUsed(40L)).thenReturn(List.of());
        ChangeRequest ecr = ecrService.create(request("PART_STATE_CHANGE",
                "{\"partId\":40,\"targetState\":\"FROZEN\"}"), 7L);

        // 流程在途：不执行
        when(workflowClient.findByBiz("ECR", ecr.getId())).thenReturn(
                new WorkflowClient.InstanceView(ecr.getWorkflowInstanceId(), "ecr-review", "RUNNING", "review"));
        ecrService.detail(ecr.getId());
        verify(materialClient, never()).applyPartLifecycle(anyLong(), org.mockito.ArgumentMatchers.anyString());

        // 流程完成：执行禁用
        when(workflowClient.findByBiz("ECR", ecr.getId())).thenReturn(
                new WorkflowClient.InstanceView(ecr.getWorkflowInstanceId(), "ecr-review", "COMPLETED", "end"));
        when(materialClient.applyPartLifecycle(40L, "FROZEN"))
                .thenReturn(new MaterialClient.PartView(40L, "P40", "禁用料", "FROZEN"));
        EcrDetailResponse done = ecrService.detail(ecr.getId());
        assertThat(done.getState()).isEqualTo("APPROVED");
        assertThat(done.getApplyState()).isEqualTo("APPLIED");
        assertThat(done.getApplyResult()).contains("FROZEN");
        verify(materialClient).applyPartLifecycle(40L, "FROZEN");
    }

    @Test
    @DisplayName("通用 ECR：无执行动作；未知类型拒绝")
    void genericAndUnknownType() {
        EcrRequest generic = new EcrRequest();
        generic.setTitle("通用变更");
        ChangeRequest ecr = ecrService.create(generic, 7L);
        assertThat(ecr.getChangeType()).isEqualTo("GENERIC");
        assertThat(ecr.getApplyState()).isNull();

        assertThatThrownBy(() -> ecrService.create(request("NO_SUCH_TYPE", "{}"), 7L))
                .isInstanceOf(BizException.class);
    }
}

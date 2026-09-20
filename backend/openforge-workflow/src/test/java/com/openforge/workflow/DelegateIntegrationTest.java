package com.openforge.workflow;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.openforge.workflow.client.NotifyClient;
import com.openforge.workflow.entity.WorkflowDelegate;
import com.openforge.workflow.entity.WorkflowInstance;
import com.openforge.workflow.entity.WorkflowTask;
import com.openforge.workflow.mapper.WorkflowDelegateMapper;
import com.openforge.workflow.mapper.WorkflowTaskMapper;
import com.openforge.workflow.service.DelegateService;
import com.openforge.workflow.service.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 审批委托集成（v1.23 设计 §2，H2）：查询期虚拟收件箱、def_key 范围过滤、
 * 代办办理 delegated_from 追溯、过期规则失效、越权撤销按不存在应答。
 */
@SpringBootTest
class DelegateIntegrationTest {

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private DelegateService delegateService;

    @Autowired
    private WorkflowTaskMapper taskMapper;

    @Autowired
    private WorkflowDelegateMapper delegateMapper;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @MockBean
    private NotifyClient notifyClient;

    private static final String DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START"},
                {"id": "a1", "type": "APPROVAL", "name": "初审", "assignee": {"type": "USER", "value": "7"}},
                {"id": "end", "type": "END"}
              ],
              "edges": [
                {"from": "start", "to": "a1"},
                {"from": "a1", "to": "end"}
              ]
            }
            """;

    @AfterEach
    void cleanRules() {
        // 同名 H2 库跨测试类共享：清空委托规则，防止生效规则泄漏污染其他类的权限语义
        delegateMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
    }

    @BeforeEach
    void stubPermissions() {
        when(permissionQueryClient.fetch(1L)).thenReturn(new PermissionView(1L, "NORMAL", List.of("ADMIN"), List.of()));
        when(permissionQueryClient.fetch(7L)).thenReturn(new PermissionView(7L, "NORMAL", List.of("ENGINEER"), List.of()));
        when(permissionQueryClient.fetch(2L)).thenReturn(new PermissionView(2L, "NORMAL", List.of(), List.of()));
    }

    private WorkflowDelegate rule(long principal, long agent, String defKey,
                                  LocalDateTime start, LocalDateTime end, String remark) {
        return delegateService.create(principal, agent, defKey, start, end, remark);
    }

    /** 部署并启动一条仅委托测试用的流程，返回停留在初审节点的任务。 */
    private WorkflowTask taskOfPrincipal(long initiator) {
        engine.deploy("delegate-flow", "委托测试流", DEFINITION, initiator);
        WorkflowInstance inst = engine.start("delegate-flow", "TEST", 1L, Map.of(), initiator);
        return engine.myTasks(7L).stream()
                .filter(t -> t.getInstanceId().equals(inst.getId()))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("自委托与时间倒置被拒绝")
    void rejectInvalidRules() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> rule(7L, 7L, null, now.minusHours(1), null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("不能委托给自己");
        assertThatThrownBy(() -> rule(7L, 1L, null, now, now.minusHours(1), null))
                .isInstanceOf(BizException.class).hasMessageContaining("结束时间不能早于开始时间");
        assertThatThrownBy(() -> rule(7L, 1L, null, null, null, null))
                .isInstanceOf(BizException.class).hasMessageContaining("开始时间必填");
    }

    @Test
    @DisplayName("生效规则：待办并集+委托标记+代办办理追溯 delegated_from")
    void delegationUnionAndAct() {
        WorkflowTask task = taskOfPrincipal(9L);
        rule(7L, 1L, null, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1), null);

        List<WorkflowTask> agentTasks = engine.myTasks(1L);
        WorkflowTask viaRule = agentTasks.stream().filter(t -> t.getId().equals(task.getId())).findFirst().orElseThrow();
        assertThat(viaRule.getViaDelegation()).isTrue();

        WorkflowInstance inst = engine.act(task.getId(), 1L, "approve", "出差代批");
        assertThat(inst.getState()).isEqualTo("COMPLETED");
        WorkflowTask acted = taskMapper.selectById(task.getId());
        assertThat(acted.getDelegatedFrom()).isEqualTo(7L); // 原指派人已追溯
        assertThat(acted.getAssigneeId()).isEqualTo(1L);    // 实际办理人
    }

    @Test
    @DisplayName("过期规则不并入待办；def_key 范围外不并入")
    void expiredAndScopedRules() {
        WorkflowTask task = taskOfPrincipal(9L);

        rule(7L, 1L, null, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1), null);
        assertThat(engine.myTasks(1L)).noneMatch(t -> t.getId().equals(task.getId()));

        rule(7L, 1L, "other-flow", LocalDateTime.now().minusHours(1), null, null);
        assertThat(engine.myTasks(1L)).noneMatch(t -> t.getId().equals(task.getId()));

        rule(7L, 1L, "delegate-flow", LocalDateTime.now().minusHours(1), null, null);
        assertThat(engine.myTasks(1L)).anyMatch(t -> t.getId().equals(task.getId()) && Boolean.TRUE.equals(t.getViaDelegation()));
    }

    @Test
    @DisplayName("无规则用户代办被拒（403）")
    void actWithoutDelegationForbidden() {
        WorkflowTask task = taskOfPrincipal(9L);
        assertThatThrownBy(() -> engine.act(task.getId(), 2L, "APPROVE", null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("撤销越权：非委托人（含被委托人）按不存在应答")
    void deleteOwnershipEnforced() {
        WorkflowDelegate r = rule(7L, 1L, null, LocalDateTime.now().minusHours(1), null, null);
        assertThatThrownBy(() -> delegateService.delete(r.getId(), 2L))
                .isInstanceOf(BizException.class).hasMessageContaining("委托规则不存在");
        assertThatThrownBy(() -> delegateService.delete(r.getId(), 1L)) // 被委托人也不能撤销
                .isInstanceOf(BizException.class).hasMessageContaining("委托规则不存在");
        delegateService.delete(r.getId(), 7L); // 委托人本人可撤销
        assertThat(delegateService.mine(7L)).noneMatch(x -> x.getId().equals(r.getId()));
    }
}

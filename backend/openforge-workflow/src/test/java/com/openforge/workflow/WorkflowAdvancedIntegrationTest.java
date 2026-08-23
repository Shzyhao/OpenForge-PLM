package com.openforge.workflow;

import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.openforge.workflow.entity.WorkflowInstance;
import com.openforge.workflow.entity.WorkflowTask;
import com.openforge.workflow.service.WorkflowEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** M3-2 引擎增强：会签（ALL）、或签（ANY）、驳回回退（rejectTo）。 */
@SpringBootTest
class WorkflowAdvancedIntegrationTest {

    @Autowired
    private WorkflowEngine engine;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    /** 双人会签节点（任一驳回回退到初审），初审单人 */
    private static final String SIGN_DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START"},
                {"id": "a1", "type": "APPROVAL", "name": "初审", "assignee": {"type": "USER", "value": "7"}},
                {"id": "a2", "type": "APPROVAL", "name": "会签", "mode": "ALL",
                 "assignee": {"type": "USERS", "values": ["8", "9"]}, "rejectTo": "a1"},
                {"id": "end", "type": "END"}
              ],
              "edges": [
                {"from": "start", "to": "a1"}, {"from": "a1", "to": "a2"}, {"from": "a2", "to": "end"}
              ]
            }
            """;

    /** 或签节点 */
    private static final String ANY_DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START"},
                {"id": "a1", "type": "APPROVAL", "name": "或签", "mode": "ANY",
                 "assignee": {"type": "USERS", "values": ["8", "9"]}},
                {"id": "end", "type": "END"}
              ],
              "edges": [{"from": "start", "to": "a1"}, {"from": "a1", "to": "end"}]
            }
            """;

    @BeforeEach
    void stubPermissions() {
        when(permissionQueryClient.fetch(7L)).thenReturn(new PermissionView(7L, List.of("ENGINEER"), List.of()));
        when(permissionQueryClient.fetch(8L)).thenReturn(new PermissionView(8L, List.of(), List.of()));
        when(permissionQueryClient.fetch(9L)).thenReturn(new PermissionView(9L, List.of(), List.of()));
    }

    /** 会签两人（8/9）的待办合并视图 */
    private List<WorkflowTask> openTasks(Long instanceId) {
        var all = new java.util.ArrayList<>(engine.myTasks(8L));
        all.addAll(engine.myTasks(9L));
        return all.stream()
                .filter(t -> t.getInstanceId().equals(instanceId))
                .toList();
    }

    @Test
    @DisplayName("会签 ALL：一人通过后仍等待，全员通过才推进完成")
    void countersignRequiresAllApprovals() {
        engine.deploy("sign-flow", "会签流程", SIGN_DEFINITION, 1L);
        WorkflowInstance instance = engine.start("sign-flow", "ORDER", 300L, Map.of(), 1L);

        // 初审（用户7）
        WorkflowTask first = engine.myTasks(7L).stream()
                .filter(t -> t.getInstanceId().equals(instance.getId())).findFirst().orElseThrow();
        engine.act(first.getId(), 7L, "APPROVE", "初审通过");

        // 会签节点两条任务（用户8、9）
        List<WorkflowTask> signTasks = openTasks(instance.getId());
        assertThat(signTasks).hasSize(2);

        // 用户8 通过 → 实例仍 RUNNING（等待用户9）
        WorkflowInstance waiting = engine.act(signTasks.get(0).getId(), 8L, "APPROVE", "同意");
        assertThat(waiting.getState()).isEqualTo("RUNNING");

        // 用户9 通过 → 完成
        WorkflowTask second = openTasks(instance.getId()).stream()
                .filter(WorkflowTask::isOpen).findFirst().orElseThrow();
        WorkflowInstance done = engine.act(second.getId(), 9L, "APPROVE", "同意");
        assertThat(done.getState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("会签驳回回退：任一人驳回回到初审节点重新生成任务，另一人任务被取消")
    void countersignRejectFallsBack() {
        engine.deploy("sign-flow", "会签流程", SIGN_DEFINITION, 1L);
        WorkflowInstance instance = engine.start("sign-flow", "ORDER", 301L, Map.of(), 1L);

        WorkflowTask first = engine.myTasks(7L).stream()
                .filter(t -> t.getInstanceId().equals(instance.getId())).findFirst().orElseThrow();
        engine.act(first.getId(), 7L, "APPROVE", null);

        List<WorkflowTask> signTasks = openTasks(instance.getId());
        assertThat(signTasks).hasSize(2);

        // 用户9 驳回 → 回退到初审 a1
        WorkflowInstance fell = engine.act(signTasks.get(1).getId(), 9L, "REJECT", "材料不足");
        assertThat(fell.getState()).isEqualTo("RUNNING");
        assertThat(fell.getCurrentNode()).isEqualTo("a1");

        // 用户8 的任务被取消；初审节点重新生成任务（用户7）
        assertThat(openTasks(instance.getId())).isEmpty(); // 用户8 视角无待办（已取消）
        WorkflowTask redo = engine.myTasks(7L).stream()
                .filter(t -> t.getInstanceId().equals(instance.getId()) && t.isOpen())
                .findFirst().orElseThrow();
        assertThat(redo.getNodeId()).isEqualTo("a1");
    }

    @Test
    @DisplayName("或签 ANY：任一人通过即完成，另一人任务自动取消")
    void anySignDecidesImmediately() {
        engine.deploy("any-flow", "或签流程", ANY_DEFINITION, 1L);
        WorkflowInstance instance = engine.start("any-flow", "ORDER", 302L, Map.of(), 1L);

        List<WorkflowTask> tasks = openTasks(instance.getId());
        assertThat(tasks).hasSize(2);

        WorkflowInstance done = engine.act(tasks.get(0).getId(), 8L, "APPROVE", "一票通过");
        assertThat(done.getState()).isEqualTo("COMPLETED");

        // 用户9 的任务已被取消
        assertThat(openTasks(instance.getId())).isEmpty();
    }

    @Test
    @DisplayName("定义校验：USERS 少于 2 人拒绝；rejectTo 指向非审批节点拒绝")
    void definitionValidation() {
        String oneUser = ANY_DEFINITION.replace("[\"8\", \"9\"]", "[\"8\"]");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> engine.deploy("bad1", "单人或签", oneUser, 1L))
                .isInstanceOf(com.openforge.common.api.BizException.class);

        String badTarget = SIGN_DEFINITION.replace("\"rejectTo\": \"a1\"", "\"rejectTo\": \"end\"");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> engine.deploy("bad2", "回退到END", badTarget, 1L))
                .isInstanceOf(com.openforge.common.api.BizException.class);
    }
}

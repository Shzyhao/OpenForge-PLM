package com.openforge.workflow;

import com.openforge.common.api.BizException;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.openforge.workflow.entity.WorkflowDef;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 流程引擎内核集成：顺序审批、条件分支、驳回、快照版本、角色认领。
 * PermissionQueryClient 以 MockBean 替换（角色数据在 auth 侧）。
 */
@SpringBootTest
class WorkflowEngineIntegrationTest {

    @Autowired
    private WorkflowEngine engine;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    /** 审批人1=用户7，条件节点金额>1000 走角色 ADMIN 审批，否则直达结束 */
    private static final String DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START"},
                {"id": "a1", "type": "APPROVAL", "name": "初审", "assignee": {"type": "USER", "value": "7"}},
                {"id": "c1", "type": "CONDITION", "name": "金额分支", "rules": [
                  {"expr": "#amount > 1000", "to": "a2"}, {"to": "end"}]},
                {"id": "a2", "type": "APPROVAL", "name": "经理审批", "assignee": {"type": "ROLE", "value": "ADMIN"}},
                {"id": "end", "type": "END"}
              ],
              "edges": [
                {"from": "start", "to": "a1"},
                {"from": "a1", "to": "c1"},
                {"from": "a2", "to": "end"}
              ]
            }
            """;

    @BeforeEach
    void stubPermissions() {
        when(permissionQueryClient.fetch(1L)).thenReturn(new PermissionView(1L, "NORMAL", List.of("ADMIN"), List.of()));
        when(permissionQueryClient.fetch(7L)).thenReturn(new PermissionView(7L, "NORMAL", List.of("ENGINEER"), List.of()));
    }

    private WorkflowInstance startWith(Map<String, Object> vars) {
        return engine.start("test-flow", "ORDER", 100L, vars, 1L);
    }

    @Test
    @DisplayName("低金额：初审通过后直达结束（条件默认分支）")
    void lowAmountGoesStraightToEnd() {
        engine.deploy("test-flow", "测试流程", DEFINITION, 1L);
        WorkflowInstance instance = startWith(Map.of("amount", 500));

        assertThat(instance.getState()).isEqualTo("RUNNING");
        WorkflowTask first = taskOf(instance);
        assertThat(first.getAssigneeId()).isEqualTo(7L);

        WorkflowInstance done = engine.act(first.getId(), 7L, "APPROVE", "通过");
        assertThat(done.getState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("高金额：初审通过后进入角色审批，角色用户可办理并完成")
    void highAmountGoesThroughRoleApproval() {
        engine.deploy("test-flow", "测试流程", DEFINITION, 1L);
        WorkflowInstance instance = startWith(Map.of("amount", 5000));

        WorkflowTask first = taskOf(instance);
        engine.act(first.getId(), 7L, "APPROVE", "初审通过");

        // 第二个节点是 ADMIN 角色任务，在用户1(ADMIN)的待办中
        WorkflowTask second = engine.myTasks(1L).stream()
                .filter(t -> t.getInstanceId().equals(instance.getId()) && t.isOpen())
                .findFirst().orElseThrow();
        assertThat(second.getCandidateRole()).isEqualTo("ADMIN");
        assertThat(second.getAssigneeId()).isNull();

        // ADMIN(用户1)在待办列表可见并办理
        assertThat(engine.myTasks(1L)).extracting(WorkflowTask::getId).contains(second.getId());
        WorkflowInstance done = engine.act(second.getId(), 1L, "APPROVE", "经理同意");
        assertThat(done.getState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("驳回：实例终止为 REJECTED")
    void rejectTerminatesInstance() {
        engine.deploy("test-flow", "测试流程", DEFINITION, 1L);
        WorkflowInstance instance = startWith(Map.of("amount", 100));

        WorkflowTask first = taskOf(instance);
        WorkflowInstance rejected = engine.act(first.getId(), 7L, "REJECT", "资料不全");
        assertThat(rejected.getState()).isEqualTo("REJECTED");
    }

    /** 可视化设计器产物：节点携带 x/y 布局坐标（引擎不读，仅设计器回显）——钉住未知字段宽容契约。 */
    private static final String DESIGNED_DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START", "x": 60, "y": 160},
                {"id": "a1", "type": "APPROVAL", "name": "初审", "assignee": {"type": "USER", "value": "7"},
                 "mode": "ALL", "x": 300, "y": 160},
                {"id": "end", "type": "END", "x": 560, "y": 160}
              ],
              "edges": [
                {"from": "start", "to": "a1"}, {"from": "a1", "to": "end"}
              ]
            }
            """;

    @Test
    @DisplayName("设计器坐标定义：携带 x/y 的节点可部署、原样存储且可执行")
    void designedDefinitionWithLayoutCoordinatesDeploysAndRuns() {
        WorkflowDef def = engine.deploy("designed-flow", "设计器流程", DESIGNED_DEFINITION, 1L);
        // 定义原样存储（坐标不丢失，设计器回显依赖此契约）
        assertThat(def.getDefinition()).contains("\"x\": 60");

        WorkflowInstance instance = engine.start("designed-flow", "ORDER", 200L, Map.of(), 1L);
        WorkflowTask first = taskOf(instance);
        assertThat(first.getNodeId()).isEqualTo("a1");
        WorkflowInstance done = engine.act(first.getId(), 7L, "APPROVE", "通过");
        assertThat(done.getState()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("权限：非指派人也无对应角色不可办理")
    void unauthorizedUserCannotAct() {
        engine.deploy("test-flow", "测试流程", DEFINITION, 1L);
        WorkflowInstance instance = startWith(Map.of("amount", 100));

        WorkflowTask first = taskOf(instance);
        // 用户1 是 ADMIN，但节点指派给用户7 —— ADMIN 无该任务权限
        assertThatThrownBy(() -> engine.act(first.getId(), 1L, "APPROVE", null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("定义快照：在途实例按启动时版本走完，不受新版定义影响")
    void snapshotIsolation() {
        WorkflowDef def = engine.deploy("snap-flow", "快照流程", DEFINITION, 1L);
        WorkflowInstance instance = engine.start("snap-flow", "ORDER", 200L, Map.of("amount", 500), 1L);
        assertThat(instance.getDefVersion()).isEqualTo(def.getVersion());

        // 部署破坏性新版（无 c1 出边——在途实例不应受影响）
        String broken = DEFINITION.replace("{\"from\": \"a1\", \"to\": \"c1\"}", "{\"from\": \"a1\", \"to\": \"end\"}");
        engine.deploy("snap-flow", "快照流程v2", broken, 1L);

        WorkflowTask first = taskOf(instance);
        WorkflowInstance done = engine.act(first.getId(), 7L, "APPROVE", "按旧版走");
        assertThat(done.getState()).isEqualTo("COMPLETED"); // 旧快照: a1→c1(默认分支)→end
    }

    @Test
    @DisplayName("定义校验：缺默认分支的条件节点拒绝部署")
    void invalidDefinitionRejected() {
        String bad = """
                {"nodes": [
                  {"id":"start","type":"START"},
                  {"id":"c","type":"CONDITION","rules":[{"expr":"a>1","to":"end"}]},
                  {"id":"end","type":"END"}],
                 "edges":[{"from":"start","to":"c"},{"from":"c","to":"end"}]}
                """;
        assertThatThrownBy(() -> engine.deploy("bad-flow", "坏流程", bad, 1L))
                .isInstanceOf(BizException.class);
    }

    private WorkflowTask taskOf(WorkflowInstance instance) {
        List<WorkflowTask> tasks = engine.myTasks(7L);
        return tasks.stream()
                .filter(t -> t.getInstanceId().equals(instance.getId()) && t.isOpen())
                .findFirst()
                .orElseThrow();
    }
}

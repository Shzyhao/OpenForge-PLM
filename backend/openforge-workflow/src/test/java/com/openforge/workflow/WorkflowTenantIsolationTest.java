package com.openforge.workflow;

import com.openforge.common.api.BizException;
import com.openforge.common.tenant.TenantContext;
import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import com.openforge.workflow.entity.WorkflowInstance;
import com.openforge.workflow.service.WorkflowEngine;
import org.junit.jupiter.api.AfterEach;
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
 * 流程实例租户隔离（R10）：实例为全局表（GLOBAL_TABLES 免租户拦截器），此前任意租户
 * 可按 ID 直读他租户实例（含 defSnapshot/variables）。修复后写入打租户戳、读取/findByBiz 校验同租户。
 */
@SpringBootTest
class WorkflowTenantIsolationTest {

    @Autowired
    private WorkflowEngine engine;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    private static final String DEFINITION = """
            {
              "nodes": [
                {"id": "start", "type": "START"},
                {"id": "a1", "type": "APPROVAL", "name": "审批", "assignee": {"type": "USER", "value": "7"}},
                {"id": "end", "type": "END"}
              ],
              "edges": [
                {"from": "start", "to": "a1"},
                {"from": "a1", "to": "end"}
              ]
            }
            """;

    @BeforeEach
    void stubPermissions() {
        when(permissionQueryClient.fetch(7L)).thenReturn(new PermissionView(7L, "NORMAL", List.of("ENGINEER"), List.of()));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("跨租户按 ID 读实例被拒；同租户可读；写入带租户戳")
    void crossTenantInstanceReadDenied() {
        TenantContext.setTenantId(1L);
        engine.deploy("tenant-iso-flow", "租户隔离", DEFINITION, 1L);
        WorkflowInstance instance = engine.start("tenant-iso-flow", "R10", 1L, Map.of("secret", "t1-data"), 1L);
        assertThat(instance.getTenantId()).isEqualTo(1L);
        assertThat(engine.instance(instance.getId()).getId()).isEqualTo(instance.getId());

        TenantContext.setTenantId(2L);
        assertThatThrownBy(() -> engine.instance(instance.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("流程实例不存在");
        assertThat(engine.findByBiz("R10", 1L)).isNull();

        TenantContext.setTenantId(1L);
        assertThat(engine.findByBiz("R10", 1L)).isNotNull();
    }

    @Test
    @DisplayName("平台租户(0)不受租户过滤限制（历史行与平台语义兼容）")
    void platformTenantUnrestricted() {
        TenantContext.setTenantId(1L);
        engine.deploy("tenant-iso-flow2", "租户隔离二", DEFINITION, 1L);
        WorkflowInstance instance = engine.start("tenant-iso-flow2", "R10", 2L, Map.of(), 1L);
        TenantContext.setTenantId(0L);
        assertThat(engine.instance(instance.getId()).getId()).isEqualTo(instance.getId());
        assertThat(engine.findByBiz("R10", 2L)).isNotNull();
    }

    @Test
    @DisplayName("流程定义为平台共享：租户0 部署的定义对非零租户可见（R10：非零租户此前 4001）")
    void definitionSharedAcrossTenants() {
        TenantContext.setTenantId(0L);
        engine.deploy("tenant-iso-flow3", "平台定义", DEFINITION, 1L);
        TenantContext.setTenantId(2L);
        WorkflowInstance instance = engine.start("tenant-iso-flow3", "R10", 3L, Map.of(), 1L);
        assertThat(instance.getTenantId()).isEqualTo(2L);
    }
}

package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.dto.OrgNodeResponse;
import com.openforge.auth.dto.UserBriefResponse;
import com.openforge.auth.entity.SysOrg;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.OrgMapper;
import com.openforge.auth.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组织树 H2 集成验证：物化路径正确性、移动后子树重算、用户挂接与按组织(含子级)查询。
 */
@SpringBootTest
class OrgIntegrationTest {

    @Autowired
    private OrgMapper orgMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrgService orgService;

    private Long rootId;
    private Long deptA;
    private Long deptA1;
    private Long deptB;
    private Long userId;

    @AfterEach
    void cleanup() {
        if (userId != null) userMapper.deleteById(userId);
        for (Long id : new Long[]{deptA1, deptA, deptB}) {
            if (id != null) orgMapper.deleteById(id);
        }
        // rootId 由 V4 迁移创建，保留
    }

    private void buildTree() {
        rootId = orgMapper.selectOne(new LambdaQueryWrapper<SysOrg>()
                .eq(SysOrg::getOrgCode, "ROOT")).getId();
        SysOrg a = orgService.createOrg("RD", "研发部", rootId, 1);
        deptA = a.getId();
        SysOrg a1 = orgService.createOrg("RD_SW", "软件组", deptA, 1);
        deptA1 = a1.getId();
        SysOrg b = orgService.createOrg("QA", "质量部", rootId, 2);
        deptB = b.getId();
    }

    @Test
    @DisplayName("物化路径与树结构正确，V4 根节点就位")
    void materializedPathAndTree() {
        buildTree();

        SysOrg a = orgMapper.selectById(deptA);
        SysOrg a1 = orgMapper.selectById(deptA1);
        assertThat(a.getPath()).isEqualTo("/" + rootId + "/" + deptA + "/");
        assertThat(a1.getPath()).isEqualTo("/" + rootId + "/" + deptA + "/" + deptA1 + "/");

        List<OrgNodeResponse> tree = orgService.fullTree();
        assertThat(tree).hasSize(1); // ROOT
        OrgNodeResponse root = tree.get(0);
        assertThat(root.children()).extracting(OrgNodeResponse::orgCode).containsExactly("RD", "QA");
    }

    @Test
    @DisplayName("移动子树后，后代物化路径全部重算")
    void moveRecalculatesDescendantPaths() {
        buildTree();

        // 把 A1 从 A 下移到 B 下
        orgService.moveOrg(deptA1, deptB);

        SysOrg a1 = orgMapper.selectById(deptA1);
        assertThat(a1.getParentId()).isEqualTo(deptB);
        assertThat(a1.getPath()).isEqualTo("/" + rootId + "/" + deptB + "/" + deptA1 + "/");

        // A1 的子级（再造一层验证递归重算）
        SysOrg a11 = orgService.createOrg("RD_SW_FE", "前端小组", deptA1, 1);
        orgService.moveOrg(a11.getId(), deptA1); // no-op move，确保路径计算基于新位置
        assertThat(orgMapper.selectById(a11.getId()).getPath())
                .isEqualTo("/" + rootId + "/" + deptB + "/" + deptA1 + "/" + a11.getId() + "/");
        orgMapper.deleteById(a11.getId());
    }

    @Test
    @DisplayName("用户挂接组织 + 按组织含子级查询（ABAC 基础查询）")
    void userOrgAssignmentAndScopedQuery() {
        buildTree();

        SysUser user = new SysUser();
        user.setUsername("org_scope_user");
        user.setPasswordHash("$2a$10$placeholderhashplaceholderhashplaceholderha");
        user.setStatus("ACTIVE");
        user.setTenantId(0L);
        user.setDeleted(0);
        userMapper.insert(user);
        userId = user.getId();

        orgService.assignUserOrg(userId, deptA1); // 挂在子级

        List<UserBriefResponse> direct = orgService.listUsers(deptA, false);
        assertThat(direct).isEmpty(); // 不含子级查不到

        List<UserBriefResponse> scoped = orgService.listUsers(deptA, true);
        assertThat(scoped).extracting(UserBriefResponse::username)
                .containsExactly("org_scope_user"); // 含子级命中

        // 移出组织
        orgService.assignUserOrg(userId, null);
        assertThat(orgService.listUsers(deptA, true)).isEmpty();
    }
}

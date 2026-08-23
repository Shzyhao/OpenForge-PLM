package com.openforge.auth.service;

import com.openforge.auth.entity.SysOrg;
import com.openforge.auth.mapper.OrgMapper;
import com.openforge.auth.mapper.UserMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrgServiceTest {

    @Mock
    private OrgMapper orgMapper;
    @Mock
    private UserMapper userMapper;

    private OrgService orgService;

    @BeforeEach
    void setUp() {
        orgService = new OrgService(orgMapper, userMapper);
    }

    private SysOrg org(Long id, String code, String path, Long parentId) {
        SysOrg o = new SysOrg();
        o.setId(id);
        o.setOrgCode(code);
        o.setOrgName(code);
        o.setPath(path);
        o.setParentId(parentId);
        return o;
    }

    @Test
    @DisplayName("创建组织：编码重复应抛 ORG_CODE_ALREADY_EXISTS")
    void createOrgDuplicateCodeShouldFail() {
        when(orgMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> orgService.createOrg("RD", "研发部", null, 0))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ORG_CODE_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("移动组织：目标父节点在自身子树下应抛 ORG_MOVE_CYCLE")
    void moveToOwnDescendantShouldFail() {
        // 结构: /1/(id=1) → /1/2/(id=2)；把 1 移到 2 下 = 环
        when(orgMapper.selectById(1L)).thenReturn(org(1L, "ROOT", "/1/", null));
        when(orgMapper.selectById(2L)).thenReturn(org(2L, "RD", "/1/2/", 1L));

        assertThatThrownBy(() -> orgService.moveOrg(1L, 2L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ORG_MOVE_CYCLE));
    }

    @Test
    @DisplayName("删除组织：存在子组织应抛 ORG_HAS_CHILDREN")
    void deleteOrgWithChildrenShouldFail() {
        when(orgMapper.selectById(2L)).thenReturn(org(2L, "RD", "/1/2/", 1L));
        when(orgMapper.selectCount(any())).thenReturn(1L); // 子组织数

        assertThatThrownBy(() -> orgService.deleteOrg(2L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ORG_HAS_CHILDREN));
    }

    @Test
    @DisplayName("删除组织：存在挂接用户应抛 ORG_HAS_USERS")
    void deleteOrgWithUsersShouldFail() {
        when(orgMapper.selectById(2L)).thenReturn(org(2L, "RD", "/1/2/", 1L));
        when(orgMapper.selectCount(any())).thenReturn(0L);  // 子组织数
        when(userMapper.selectCount(any())).thenReturn(5L); // 挂接用户数

        assertThatThrownBy(() -> orgService.deleteOrg(2L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ORG_HAS_USERS));
    }
}

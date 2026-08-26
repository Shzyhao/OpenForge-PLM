package com.openforge.auth.controller;

import com.openforge.auth.mapper.RoleMapper;
import com.openforge.auth.service.NumberRuleService;
import com.openforge.auth.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部接口验证：令牌缺失/错误拒绝；正确令牌可取号与查权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NumberRuleService numberRuleService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleMapper roleMapper;

    private static final String TOKEN = "openforge-internal-dev-token";

    @Test
    @DisplayName("缺少内部令牌：401")
    void missingTokenShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/numbers/next/part"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    @DisplayName("错误内部令牌：401")
    void wrongTokenShouldBeRejected() throws Exception {
        mockMvc.perform(post("/api/v1/internal/numbers/next/part")
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确令牌：取号成功（V5 内置 part 规则）")
    void validTokenCanTakeNumber() throws Exception {
        mockMvc.perform(post("/api/v1/internal/numbers/next/part")
                        .header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    @DisplayName("正确令牌：返回权限视图（roles + permissions）")
    void validTokenCanQueryPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/internal/permissions/1")
                        .header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("内部权限点创建：幂等 + ADMINS 绑定 + 无令牌拒绝")
    void internalEnsurePermission() throws Exception {
        // 无令牌拒绝
        mockMvc.perform(post("/api/v1/internal/permissions")
                        .contentType("application/json")
                        .content("{\"permCode\":\"equipment:view\",\"permName\":\"设备查看\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        // 首次创建 + 绑定 ADMINS（V14 起 ADMIN 已更名）
        mockMvc.perform(post("/api/v1/internal/permissions")
                        .header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"permCode\":\"equipment:view\",\"permName\":\"设备查看\",\"bindRoleCodes\":[\"ADMINS\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true));

        // 幂等重试：created=false，不报权限点冲突
        mockMvc.perform(post("/api/v1/internal/permissions")
                        .header("X-Internal-Token", TOKEN)
                        .contentType("application/json")
                        .content("{\"permCode\":\"equipment:view\",\"permName\":\"设备查看\",\"bindRoleCodes\":[\"ADMINS\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(false));

        // ADMINS 角色确实获得该权限点
        com.openforge.auth.entity.SysRole admins = roleMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.openforge.auth.entity.SysRole>()
                        .eq(com.openforge.auth.entity.SysRole::getRoleCode, "ADMINS"));
        org.assertj.core.api.Assertions.assertThat(admins).isNotNull();
        org.assertj.core.api.Assertions.assertThat(permissionService.getPermissionCodesOfRole(admins.getId()))
                .contains("equipment:view");
    }
}

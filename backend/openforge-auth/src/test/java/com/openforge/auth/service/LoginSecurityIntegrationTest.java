package com.openforge.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.auth.entity.SysUser;
import com.openforge.auth.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 登录安全（方案 E2/E9）：密码过期三态 + 失败锁定。 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    private Long createUserWithAge(String username, long daysAgo, int firstLoginChange) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("Passw0rd123"));
        u.setDisplayName(username);
        u.setStatus("ACTIVE");
        u.setUserType("NORMAL");
        u.setPasswordUpdatedAt(LocalDateTime.now().minusDays(daysAgo));
        u.setFirstLoginChange(firstLoginChange);
        u.setFailedLoginCount(0);
        u.setTenantId(0L);
        u.setDeleted(0);
        userMapper.insert(u);
        return u.getId();
    }

    private String login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("密码状态三态：过期/临期/正常/首登强制")
    void passwordStatusThreeStates() throws Exception {
        createUserWithAge("pw_expired", 200, 0);
        createUserWithAge("pw_soon", 175, 0);
        createUserWithAge("pw_ok", 10, 0);
        createUserWithAge("pw_force", 1, 1);

        assertThat(login("pw_expired", "Passw0rd123")).contains("EXPIRED");
        assertThat(login("pw_soon", "Passw0rd123")).contains("EXPIRING_SOON");
        assertThat(login("pw_ok", "Passw0rd123")).contains("\"passwordStatus\":\"OK\"");
        assertThat(login("pw_force", "Passw0rd123")).contains("FORCE_CHANGE");
    }

    @Test
    @DisplayName("登录失败 5 次锁定 15 分钟，期间拒绝（2005）")
    void loginLockAfterFiveFailures() throws Exception {
        createUserWithAge("pw_lock", 1, 0);
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"pw_lock\",\"password\":\"wrongpass1\"}"))
                    .andExpect(jsonPath("$.code").value(2002));
        }
        // 第 6 次：正确密码也被锁定拦截
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pw_lock\",\"password\":\"Passw0rd123\"}"))
                .andExpect(jsonPath("$.code").value(2005));

        SysUser locked = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "pw_lock"));
        assertThat(locked.getLockedUntil()).isAfter(LocalDateTime.now());
    }
}

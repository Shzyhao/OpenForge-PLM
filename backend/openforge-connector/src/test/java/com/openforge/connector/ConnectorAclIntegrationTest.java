package com.openforge.connector;

import com.openforge.security.PermissionQueryClient;
import com.openforge.security.PermissionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 连接器级 ACL（十轮改进）：aclRoles 白名单只约束运行时 invoke；
 * 空白名单 = 租户内持 conn:invoke 者皆可；读端点走 conn:view（与 manage 分离）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConnectorAclIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PermissionQueryClient permissionQueryClient;

    @BeforeEach
    void stubPermissions() {
        // 用户1=ADMINS（manage/invoke/view 全量）、用户7=ENGINEER（view+invoke——过门禁但可被 ACL 拦）、用户8=VIEWER（仅 view）
        when(permissionQueryClient.fetch(1L)).thenReturn(new PermissionView(1L, "NORMAL", List.of("ADMINS"),
                List.of("conn:manage", "conn:invoke", "conn:view")));
        when(permissionQueryClient.fetch(7L)).thenReturn(new PermissionView(7L, "NORMAL", List.of("ENGINEER"),
                List.of("conn:view", "conn:invoke")));
        when(permissionQueryClient.fetch(8L)).thenReturn(new PermissionView(8L, "NORMAL", List.of("VIEWER"),
                List.of("conn:view")));
    }

    private String createBody(String code, String aclJson) {
        return """
                {"connCode":"%s","connName":"ACL测试","connType":"HTTP_REST",
                 "spec":{"schemaVersion":1,"method":"GET","url":"http://localhost:8080/actuator/health"},
                 "aclRoles":%s}""".formatted(code, aclJson);
    }

    @Test
    @DisplayName("白名单角色命中：通过 ACL，到未发布检查（6004 而非 2004）")
    void aclHitPassesToPublishedCheck() throws Exception {
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("acl_hit", "[\"ADMINS\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aclRoles[0]").value("ADMINS"));

        mockMvc.perform(post("/api/v1/connectors/invoke/acl_hit").header("X-User-Id", 1)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6004));
    }

    @Test
    @DisplayName("白名单不命中：invoke 拒绝 2004；读详情按 conn:view 放行")
    void aclMissDenied() throws Exception {
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("acl_miss", "[\"ADMINS\"]"))).andExpect(status().isOk());

        // ENGINEER 不在白名单 → ACL 拒绝（FORBIDDEN → HTTP 403 + code 2004，而非未发布 6004）
        mockMvc.perform(post("/api/v1/connectors/invoke/acl_miss").header("X-User-Id", 7)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(2004));

        // 读详情走 conn:view：ENGINEER 可读（v30 种子绑定 ENGINEER）
        mockMvc.perform(get("/api/v1/connectors?query=1").header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("空白名单 = 不限制：ENGINEER 通过 ACL（未发布 6004）")
    void emptyAclUnrestricted() throws Exception {
        mockMvc.perform(post("/api/v1/connectors").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("acl_open", "null"))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/connectors/invoke/acl_open").header("X-User-Id", 7)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(6004));
    }
}

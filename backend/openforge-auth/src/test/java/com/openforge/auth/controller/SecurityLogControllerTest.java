package com.openforge.auth.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全日志分页结构回归（全页面浏览器级巡检实锤，约定 #9）：
 * 此前直返 MyBatis-Plus Page（records/size），前端读 list 得 undefined——
 * 表格"暂无数据"而总数正常。断言统一 PageResponse{list,total,page,pageSize}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("登录日志：PageResponse 结构（list 为数组 + total 字段）")
    void loginLogsUseStandardPageShape() throws Exception {
        mockMvc.perform(get("/api/v1/security/login-logs")
                        .header("X-User-Id", "1")   // admin（user:manage）
                        .header("X-User-Tenant", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.records").doesNotExist());
    }

    @Test
    @DisplayName("操作审计：PageResponse 结构")
    void auditLogsUseStandardPageShape() throws Exception {
        mockMvc.perform(get("/api/v1/security/audit-logs")
                        .header("X-User-Id", "1")
                        .header("X-User-Tenant", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.records").doesNotExist());
    }
}

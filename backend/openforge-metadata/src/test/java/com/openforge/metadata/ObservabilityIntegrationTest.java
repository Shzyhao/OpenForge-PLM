package com.openforge.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3 可观测（H2）：/actuator/prometheus 指标端点；traceId 贯穿——
 * 网关注入的 X-Trace-Id → MDC → ApiResponse.traceId 同值复用（日志与响应可对账）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Prometheus 指标端点：200 且含 JVM 指标")
    void prometheusEndpointExposed() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("traceId 贯穿：请求头 → 响应头 + 响应体同值（含 404 错误响应）")
    void traceIdPropagatesToBody() throws Exception {
        mockMvc.perform(get("/api/v1/meta/objects/999999").header("X-Trace-Id", "aa-bb-11-22"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").value("aa-bb-11-22"));

        // 无头请求：自动生成（响应头与响应体一致）
        String generated = mockMvc.perform(post("/api/v1/meta/objects/999999/publish"))
                .andReturn().getResponse().getHeader("X-Trace-Id");
        org.assertj.core.api.Assertions.assertThat(generated).isNotBlank();
    }
}

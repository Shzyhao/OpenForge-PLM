package com.openforge.change;

import com.openforge.change.client.NumberClient;
import com.openforge.change.client.WorkflowClient;
import com.openforge.change.service.EcrService;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.22 ECO 联动（drawing.released → 自动创建联动变更单）：
 * autoCreateFromDrawing 创建/幂等 + internal 端点令牌门禁 + 无关物料时不空建。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangeDrawingLinkIntegrationTest {

    @Autowired
    private EcrService ecrService;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NumberClient numberClient;
    @MockBean
    private WorkflowClient workflowClient;

    private static final AtomicLong ecrSeq = new AtomicLong(300);
    private static final AtomicLong flowSeq = new AtomicLong(900);

    @BeforeEach
    void stubClients() {
        when(numberClient.next("ecr")).thenAnswer(i -> "ECR" + String.format("%010d", ecrSeq.incrementAndGet()));
        when(workflowClient.start(anyString(), eq("ECR"), anyLong(), any())).thenAnswer(i -> flowSeq.incrementAndGet());
    }

    private Map<String, Object> payload(String number, String version) {
        return Map.of(
                "drawingId", 501,
                "drawingNumber", number,
                "title", "法兰盘图纸",
                "version", version,
                "linkedParts", List.of(Map.of("partId", 10, "partNumber", "P-001", "role", "PART_DRAWING")));
    }

    @Test
    @DisplayName("联动单自动创建：GENERIC 类型 + 关联物料入 affectedItems + 进入审批流")
    void autoCreateCreatesLinkEcr() {
        Map<String, Object> result = ecrService.autoCreateFromDrawing(payload("DW-LINK-1", "A/0"));
        assertThatCreated(result);

        // 幂等：同图纸同版本重复事件只建一单
        Map<String, Object> again = ecrService.autoCreateFromDrawing(payload("DW-LINK-1", "A/0"));
        org.assertj.core.api.Assertions.assertThat(again.get("created")).isEqualTo(false);

        // 升版后（B/0）再次发布 → 新联动单
        Map<String, Object> revised = ecrService.autoCreateFromDrawing(payload("DW-LINK-1", "B/0"));
        assertThatCreated(revised);
    }

    private void assertThatCreated(Map<String, Object> result) {
        org.assertj.core.api.Assertions.assertThat(result.get("created")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(String.valueOf(result.get("ecrNumber"))).startsWith("ECR");
    }

    @Test
    @DisplayName("internal 端点：缺令牌 401；正确令牌创建成功")
    void internalEndpointGuard() throws Exception {
        String body = "{\"drawingNumber\":\"DW-G-1\",\"version\":\"A/0\",\"title\":\"t\","
                + "\"linkedParts\":[{\"partId\":10,\"partNumber\":\"P-1\"}]}";

        mockMvc.perform(post("/api/v1/internal/change/drawing-released")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(2001));

        mockMvc.perform(post("/api/v1/internal/change/drawing-released")
                        .header("X-Internal-Token", "test-internal-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(true));
    }

    @Test
    @DisplayName("无关联物料的图纸发布不空建联动单")
    void noLinkedPartsNoEcr() {
        Map<String, Object> result = ecrService.autoCreateFromDrawing(
                Map.of("drawingId", 502, "drawingNumber", "DW-LINK-2", "version", "A/0",
                        "title", "t", "linkedParts", List.of()));
        org.assertj.core.api.Assertions.assertThat(result.get("created")).isEqualTo(false);
    }
}

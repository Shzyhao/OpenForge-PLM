package com.openforge.common.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** 服务侧链路追踪过滤器（B3）：MDC 装载/清理、响应回显、非法头重生成。 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private record Result(String mdcInside, String responseHeader) {
    }

    private Result run(String incomingHeader) throws IOException, jakarta.servlet.ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/parts");
        if (incomingHeader != null) {
            request.addHeader(TraceIdFilter.HEADER_TRACE_ID, incomingHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcValue = new String[1];
        filter.doFilter(request, response, (req, res) -> mdcValue[0] = MDC.get(TraceIdFilter.MDC_KEY));
        return new Result(mdcValue[0], response.getHeader(TraceIdFilter.HEADER_TRACE_ID));
    }

    @Test
    @DisplayName("网关注入的追踪头 → MDC + 响应回显")
    void headerToMdcAndEcho() throws Exception {
        Result result = run("abc-123-def");
        assertThat(result.mdcInside()).isEqualTo("abc-123-def");
        assertThat(result.responseHeader()).isEqualTo("abc-123-def");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();   // 请求结束清理
    }

    @Test
    @DisplayName("无头（直连服务）→ 自动生成")
    void generatesWhenAbsent() throws Exception {
        Result result = run(null);
        assertThat(result.mdcInside()).isNotBlank();
        assertThat(result.responseHeader()).isEqualTo(result.mdcInside());
    }

    @Test
    @DisplayName("非法形态（日志注入载荷）→ 重生成安全值")
    void sanitizesMalformed() throws Exception {
        Result result = run("evil\nInjected");
        assertThat(result.mdcInside()).isNotEqualTo("evil\nInjected").isNotBlank();
    }
}

package com.openforge.common.trace;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器（B3 可观测）：网关注入的 X-Trace-Id（或自动生成）→ MDC，
 * 日志模式携带 traceId、ApiResponse 复用同一值；响应回显便于客户端对账。
 * 与 X-User-Id/X-User-Tenant 同信任模型：外部伪造值会被网关剥离重写。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class TraceIdFilter implements Filter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        try {
            if (request instanceof HttpServletRequest http && response instanceof HttpServletResponse res) {
                traceId = sanitize(http.getHeader(HEADER_TRACE_ID));
                if (traceId == null) {
                    traceId = UUID.randomUUID().toString();
                }
                MDC.put(MDC_KEY, traceId);
                res.setHeader(HEADER_TRACE_ID, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 仅放行安全的追踪 ID 形态（UUID/十六进制/短横线），防日志注入。 */
    private static String sanitize(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        return value.length() <= 64 && value.matches("[0-9a-fA-F-]+") ? value : null;
    }
}

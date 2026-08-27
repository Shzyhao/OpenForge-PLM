package com.openforge.common.tenant;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 租户头解析过滤器：X-User-Tenant（网关信任头）→ TenantContext，请求结束清理。
 * 与 X-User-Id 同信任模型：仅信任网关注入的头，网关侧会剥离外部伪造值。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantHeaderFilter implements Filter {

    public static final String HEADER_USER_TENANT = "X-User-Tenant";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (request instanceof HttpServletRequest http) {
                TenantContext.setTenantId(parse(http.getHeader(HEADER_USER_TENANT)));
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Long parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

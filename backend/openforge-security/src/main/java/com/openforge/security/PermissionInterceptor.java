package com.openforge.security;

import com.openforge.common.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 通用 @RequirePermission 校验拦截器（业务服务用，通过 HTTP 查询 auth 权限 + 缓存）。
 * auth 服务自身使用库直查版本（性能更优），两处行为保持一致。
 * 身份来自网关信任头 X-User-Id；ADMIN 角色免检兜底。
 */
public class PermissionInterceptor implements HandlerInterceptor {

    static final String HEADER_USER_ID = "X-User-Id";

    private final PermissionQueryClient permissionQueryClient;

    public PermissionInterceptor(PermissionQueryClient permissionQueryClient) {
        this.permissionQueryClient = permissionQueryClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission anno = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (anno == null) {
            anno = handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (anno == null) {
            return true;
        }

        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader == null) {
            return reject(response, 2001, "缺少网关信任头，请经由网关访问");
        }
        Long userId;
        try {
            userId = Long.valueOf(userIdHeader);
        } catch (NumberFormatException e) {
            return reject(response, 2001, "信任头格式非法");
        }

        PermissionView view = permissionQueryClient.fetch(userId);
        if (view.roles().contains("ADMIN")) {
            return true;
        }
        if (view.permissions().contains(anno.value())) {
            return true;
        }
        return reject(response, 2004, "无操作权限: " + anno.value());
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code == 2001 ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null,\"traceId\":\""
                        + UUID.randomUUID() + "\"}");
        return false;
    }
}

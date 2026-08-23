package com.openforge.auth.interceptor;

import com.openforge.auth.service.PermissionService;
import com.openforge.auth.service.RbacService;
import com.openforge.common.annotation.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * @RequirePermission 注解校验拦截器。
 * 身份来自网关信任头 X-User-Id（见 gateway AuthGlobalFilter）。
 * ADMIN 角色免检兜底（即使新权限点忘了绑定也能通过）。
 */
@Component
@RequiredArgsConstructor
public class PermissionInterceptor implements HandlerInterceptor {

    static final String HEADER_USER_ID = "X-User-Id";

    private final RbacService rbacService;
    private final PermissionService permissionService;

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
            return true; // 无注解不拦截
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

        List<String> roles = rbacService.getRoleCodesOfUser(userId);
        if (roles.contains("ADMIN")) {
            return true;
        }
        if (permissionService.getPermissionCodesOfUser(userId).contains(anno.value())) {
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

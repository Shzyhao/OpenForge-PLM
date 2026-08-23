package com.openforge.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限点校验注解。拦截器校验当前用户（网关信任头 X-User-Id）是否持有该权限点，
 * 或持有 ADMIN 角色（免检）。M1 拦截器实现在 auth 服务，M2 抽为通用 starter。
 *
 * <pre>{@code
 * @RequirePermission("role:assign")
 * @PutMapping("/users/{userId}")
 * public ApiResponse<Void> assign(...) { ... }
 * }</pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 权限点编码，如 role:assign */
    String value();
}

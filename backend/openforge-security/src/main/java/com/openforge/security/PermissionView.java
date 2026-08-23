package com.openforge.security;

import java.util.List;

/** 权限视图（与 auth 的 InternalPermissionView 结构对应）。 */
public record PermissionView(Long userId, List<String> roles, List<String> permissions) {
}

package com.openforge.security;

import java.util.List;

/** 权限视图（与 auth 的 InternalPermissionView 结构对应；userType=SUPER 为固定 admin 免检）。 */
public record PermissionView(Long userId, String userType, List<String> roles, List<String> permissions) {
}

package com.openforge.auth.dto;

import java.util.List;

/** 服务间权限视图：角色 + 权限点（消费方自行做 ADMIN 短路判断）。 */
public record InternalPermissionView(Long userId, List<String> roles, List<String> permissions) {
}

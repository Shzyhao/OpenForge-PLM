package com.openforge.auth.dto;

import java.util.List;

/** 服务间权限视图：账号类型 + 角色 + 权限点（消费方按 userType=SUPER 做免检判断）。 */
public record InternalPermissionView(Long userId, String userType, List<String> roles, List<String> permissions) {
}

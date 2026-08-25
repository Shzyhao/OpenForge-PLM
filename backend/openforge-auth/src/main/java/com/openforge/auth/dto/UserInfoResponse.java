package com.openforge.auth.dto;

import java.util.List;

/** 当前用户信息（方案 F1）：角色 + 菜单权限 + 操作权限 + 账号类型。 */
public record UserInfoResponse(Long id, String username, String displayName,
                               List<String> roles, List<String> menus,
                               List<String> permissions, String userType) {
}

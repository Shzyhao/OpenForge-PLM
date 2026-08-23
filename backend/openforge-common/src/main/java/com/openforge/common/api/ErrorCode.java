package com.openforge.common.api;

import lombok.Getter;

/**
 * 统一错误码。分段规则见开发文档 8.1：
 * 0 成功；1xxx 参数；2xxx 认证权限；3xxx 业务规则；4xxx 资源状态；5xxx 系统。
 */
@Getter
public enum ErrorCode {

    OK(0, "ok"),

    // 1xxx 参数错误
    INVALID_ARGUMENT(1000, "参数校验失败"),

    // 2xxx 认证与权限
    UNAUTHORIZED(2001, "未认证或令牌已失效"),
    BAD_CREDENTIALS(2002, "用户名或密码错误"),
    ACCOUNT_DISABLED(2003, "账号已停用"),
    FORBIDDEN(2004, "无操作权限"),

    // 3xxx 业务规则
    USERNAME_ALREADY_EXISTS(3001, "用户名已存在"),
    ROLE_CODE_ALREADY_EXISTS(3002, "角色编码已存在"),
    PERMISSION_CODE_ALREADY_EXISTS(3003, "权限点编码已存在"),

    // 4xxx 资源状态
    RESOURCE_NOT_FOUND(4001, "资源不存在"),
    ROLE_NOT_FOUND(4002, "角色不存在"),
    PERMISSION_NOT_FOUND(4003, "权限点不存在"),

    // 5xxx 系统
    INTERNAL_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

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
    ACCOUNT_LOCKED(2005, "账号已锁定，请稍后再试"),
    WEAK_PASSWORD(2006, "密码不符合策略（≥8位且包含字母与数字）"),

    // 3xxx 业务规则
    USERNAME_ALREADY_EXISTS(3001, "用户名已存在"),
    ROLE_CODE_ALREADY_EXISTS(3002, "角色编码已存在"),
    PERMISSION_CODE_ALREADY_EXISTS(3003, "权限点编码已存在"),
    ORG_CODE_ALREADY_EXISTS(3004, "组织编码已存在"),
    ORG_MOVE_CYCLE(3005, "不能移动到自身或其子组织下"),
    NUMBER_RULE_KEY_EXISTS(3006, "编号规则键已存在"),
    INVALID_STATE_TRANSITION(3007, "非法的状态流转"),
    ATTR_VALIDATION_FAILED(3008, "属性不符合分类模板"),
    BOM_CYCLE(3009, "BOM 存在循环引用"),
    META_OBJECT_KEY_EXISTS(3010, "元对象键已存在"),
    META_FIELD_INVALID(3011, "字段定义不合法"),
    META_REF_OBJECT_NOT_FOUND(3012, "引用对象不存在"),

    // 4xxx 资源状态
    RESOURCE_NOT_FOUND(4001, "资源不存在"),
    ROLE_NOT_FOUND(4002, "角色不存在"),
    PERMISSION_NOT_FOUND(4003, "权限点不存在"),
    ORG_NOT_FOUND(4004, "组织不存在"),
    ORG_HAS_CHILDREN(4005, "存在子组织，禁止删除"),
    ORG_HAS_USERS(4006, "组织下存在用户，禁止删除"),
    NUMBER_RULE_NOT_FOUND(4007, "编号规则不存在"),
    BOM_NOT_FOUND(4008, "BOM 不存在"),
    META_OBJECT_NOT_FOUND(4009, "元对象不存在"),
    META_OBJECT_PUBLISHED(4010, "已发布对象不可修改"),
    META_OBJECT_NOT_PUBLISHED(4011, "对象未发布，动态数据不可用"),
    META_RECORD_NOT_FOUND(4012, "记录不存在"),
    MODULE_ALREADY_EXISTS(3013, "模块键或路由前缀已被占用"),
    MODULE_HAS_DEPENDENTS(4020, "存在启用中的依赖方，禁止停用"),
    MODULE_KERNEL_IMMUTABLE(4021, "内核模块禁止停用"),
    MODULE_DISABLED(4022, "依赖模块未启用"),
    MODULE_NOT_FOUND(4023, "模块不存在"),

    // 5xxx 系统
    INTERNAL_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

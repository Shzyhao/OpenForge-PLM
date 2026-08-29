package com.openforge.common.tenant;

import java.util.Set;

/**
 * 租户拦截的全局表清单（无 tenant_id 或跨租户共享语义的表）：
 * 租户 SQL 自动过滤（TenantLineInnerInterceptor）对这些表不追加 tenant_id 条件。
 * 清单依据 = 全库迁移扫描（表定义无 tenant_id 列）+ 本期保持全局语义的表
 * （RBAC/审计/日志/编号计数/模块注册/元数据定义——元数据建模本期全局共享，逐租户建模随 F3 演进）。
 */
public final class TenantTables {

    private static final Set<String> GLOBAL_TABLES = Set.of(
            // 认证与权限（RBAC 全局共享，登录查询不携带租户上下文）
            "sys_user", "sys_role", "sys_permission", "sys_role_permission", "sys_user_role",
            "sys_org",
            // 审计/日志/安全（跨租户运维视角）+ 租户主档自身 + 事件总线基础设施
            "sys_audit_log", "sys_login_log", "sys_password_history", "sys_module", "sys_tenant",
            "sys_event_outbox", "sys_event_consumed",
            // 编号（规则带租户但计数器行级全局递增）
            "sys_number_counter",
            // 元数据定义（建模与界面制品全局共享）
            "meta_object", "meta_field", "meta_object_version", "meta_form_layout",
            // 各服务无 tenant_id 列的业务从表
            "part_version", "doc_file", "knowledge_feedback", "workflow_instance", "workflow_task");

    private TenantTables() {
    }

    public static boolean isGlobal(String tableName) {
        if (tableName == null) {
            return true;
        }
        String lower = tableName.toLowerCase();
        return lower.startsWith("flyway_") || GLOBAL_TABLES.contains(lower);
    }
}

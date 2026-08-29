package com.openforge.common.tenant;

/**
 * 租户上下文（架构文档 7.3：共享库 + tenant_id 行级隔离）。
 * 值来自网关注入的 X-User-Tenant 信任头（JWT tenant claim），默认 0 = 全局/默认租户。
 * 私有化单租户部署下恒为 0，行为与无租户版本完全一致。
 */
public final class TenantContext {

    public static final long DEFAULT_TENANT = 0L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static long getTenantId() {
        Long value = CURRENT.get();
        return value == null ? DEFAULT_TENANT : value;
    }

    public static void setTenantId(Long tenantId) {
        CURRENT.set(tenantId == null ? DEFAULT_TENANT : tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}

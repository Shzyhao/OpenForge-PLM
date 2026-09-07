package com.openforge.connector.spec;

import java.util.List;
import java.util.Map;

/**
 * JDBC 只读连接器 spec（schemaVersion=1，集成编排器 MVP 设计 §4.1）。
 * 校验规则见 {@link ConnectorSpecs#parseJdbcReadonly}。
 */
public record JdbcReadonlySpec(
        int schemaVersion,
        String jdbcUrl,
        String username,
        String passwordRef,
        List<String> allowedTables,
        String sqlTemplate,
        Map<String, Object> parameterSchema,
        int maxRows,
        int timeoutMs) {
}

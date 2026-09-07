package com.openforge.connector.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.connector.spec.JdbcReadonlySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC 只读连接器（集成编排器 MVP 设计 §5）：
 * - 每数据源配置（url+user+密码摘要）一个小连接池（max=2，空闲即回收），LRU 上界 50 个池；
 * - 连接层 Connection#setReadOnly(true) + 只读账号为真正防线（静态校验只是第一层）；
 * - 命名参数 :name → PreparedStatement 位置绑定（禁止拼接）；
 * - setMaxRows(maxRows+1) 检测截断，setQueryTimeout 按 spec 超时。
 * 密码不进池 key 明文：仅参与摘要（SHA-256）。
 */
@Slf4j
@Component
public class JdbcReadonlyConnector implements ConnectorSpi {

    private static final Pattern PARAM = Pattern.compile("(?<![:\\w]):([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final int MAX_POOLS = 50;

    private final ObjectMapper objectMapper;
    private final LinkedHashMap<String, HikariDataSource> pools =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HikariDataSource> eldest) {
                    if (size() > MAX_POOLS) {
                        eldest.getValue().close();
                        log.info("JDBC 连接池 LRU 驱逐（>{}）", MAX_POOLS);
                        return true;
                    }
                    return false;
                }
            };

    public JdbcReadonlyConnector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return "JDBC_READONLY";
    }

    @Override
    public ConnectorResult execute(ConnectorExecution execution) {
        JdbcReadonlySpec spec = execution.jdbcSpec();
        ResolvedCredential credential = execution.credential();
        if (credential == null || credential.secret() == null) {
            return ConnectorResult.fail(null, "JDBC_PASSWORD 凭据缺失");
        }
        String sql = spec.sqlTemplate();
        Set<String> paramOrder = new LinkedHashSet<>();
        Matcher m = PARAM.matcher(sql);
        while (m.find()) {
            paramOrder.add(m.group(1));
        }
        String positionalSql = PARAM.matcher(sql).replaceAll("?");

        HikariDataSource pool = poolOf(spec, credential.secret());
        long start = System.currentTimeMillis();
        try (Connection connection = pool.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(positionalSql)) {
                int index = 1;
                for (String name : paramOrder) {
                    Object value = execution.params() == null ? null : execution.params().get(name);
                    statement.setObject(index++, value);
                }
                statement.setMaxRows(spec.maxRows() + 1); // 多取一行检测截断
                statement.setQueryTimeout(Math.max(1, spec.timeoutMs() / 1000));
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Map<String, Object>> rows = readRows(resultSet, spec.maxRows());
                    boolean truncated = false;
                    if (rows.size() > spec.maxRows()) {
                        rows = rows.subList(0, spec.maxRows());
                        truncated = true;
                    }
                    String body = objectMapper.writeValueAsString(Map.of(
                            "rows", rows, "count", rows.size(), "truncated", truncated));
                    return new ConnectorResult(true, null, rows.size(), body, truncated, null);
                }
            }
        } catch (Exception e) {
            return ConnectorResult.fail(null, "查询失败: " + sanitize(e.getMessage(), credential.secret())
                    + " (" + (System.currentTimeMillis() - start) + "ms)");
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet, int limit) throws Exception {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() <= limit) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i).toLowerCase(), normalize(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    /** JDBC 专有类型转为可序列化类型（时间戳 → LocalDateTime；布尔保持）。 */
    private Object normalize(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        return value;
    }

    private synchronized HikariDataSource poolOf(JdbcReadonlySpec spec, String password) {
        // 池 key 只含密码摘要，不含明文；凭据轮换自然生成新池，旧池经 LRU 驱逐关闭
        String key = Integer.toHexString((spec.jdbcUrl() + "|" + spec.username() + "|"
                + java.util.Arrays.hashCode(password.toCharArray())).hashCode());
        HikariDataSource pool = pools.get(key);
        if (pool == null || pool.isClosed()) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(spec.jdbcUrl());
            config.setUsername(spec.username());
            config.setPassword(password);
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setIdleTimeout(30_000);
            config.setConnectionTimeout(spec.timeoutMs());
            config.setPoolName("conn-" + key);
            config.setReadOnly(true);
            pool = new HikariDataSource(config);
            pools.put(key, pool);
        }
        return pool;
    }

    private String sanitize(String message, String secret) {
        if (message == null) {
            return "unknown";
        }
        if (secret != null && !secret.isEmpty() && message.contains(secret)) {
            return message.replace(secret, "***");
        }
        return message;
    }
}

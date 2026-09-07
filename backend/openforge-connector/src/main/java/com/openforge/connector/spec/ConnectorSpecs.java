package com.openforge.connector.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连接器 spec 解析与校验（集成编排器 MVP 设计 §4.1/§5）。
 * schemaVersion=1 仅支持 HTTP_REST 单步语义；JDBC_READONLY 随刀2 接入。
 * 校验失败统一 CONN_SPEC_INVALID，消息指明字段——设计态暴露问题，运行期不再猜测。
 */
public final class ConnectorSpecs {

    public static final String TYPE_HTTP_REST = "HTTP_REST";
    public static final String TYPE_JDBC_READONLY = "JDBC_READONLY";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TYPE_HTTP_REST, TYPE_JDBC_READONLY);

    /** JDBC 数据源仅放行两种驱动（连接器 MVP 设计 §5）；更多方言按需追加。 */
    private static final Set<String> JDBC_URL_PREFIXES = Set.of("jdbc:postgresql://", "jdbc:mysql://");
    /** 写关键字（词边界）：只读模板静态校验第一层；连接只读标志与只读账号为第二三层。 */
    private static final Pattern JDBC_WRITE_KEYWORD = Pattern.compile(
            "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|comment|"
                    + "call|execute|set|lock|copy|vacuum|analyze|do)\\b", Pattern.CASE_INSENSITIVE);
    /** 命名参数 :name */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<![:\\w]):([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final int JDBC_MAX_ROWS_DEFAULT = 200;
    private static final int JDBC_MAX_ROWS_MAX = 1000;

    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH");
    /** spec 显式头黑名单：认证走凭据引用；逐跳头交给 HTTP 栈 */
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "authorization", "host", "content-length", "connection", "transfer-encoding");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_]*)}}");

    private static final int TIMEOUT_MIN = 100;
    private static final int TIMEOUT_MAX = 30_000;
    private static final int MAX_ATTEMPTS_MAX = 5;
    private static final int BACKOFF_MAX = 10_000;

    private ConnectorSpecs() {
    }

    /** conn_type 是否在 MVP 支持范围。 */
    public static void checkType(String connType) {
        if (connType == null || !SUPPORTED_TYPES.contains(connType)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "不支持的连接器类型: " + connType + "（MVP 支持 " + SUPPORTED_TYPES + "）");
        }
    }

    /** 解析并校验 HTTP_REST spec；任何非法输入抛 CONN_SPEC_INVALID。 */
    public static HttpRestSpec parseHttpRest(Object specNode, ObjectMapper mapper,
                                             boolean credentialRefExists) {
        Map<String, Object> root = asMap(specNode, "spec");
        int schemaVersion = intOf(root.get("schemaVersion"), 0, "schemaVersion");
        if (schemaVersion != 1) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "schemaVersion 仅支持 1");
        }

        String method = strOf(root.get("method"), "method").toUpperCase();
        if (!HTTP_METHODS.contains(method)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "method 仅支持 GET/POST/PUT/PATCH: " + method);
        }
        String url = strOf(root.get("url"), "url");
        checkUrl(url);

        Map<String, String> headers = new LinkedHashMap<>();
        Object headersNode = root.get("headers");
        if (headersNode != null) {
            for (Map.Entry<String, Object> e : asMap(headersNode, "headers").entrySet()) {
                if (FORBIDDEN_HEADERS.contains(e.getKey().toLowerCase())) {
                    throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                            "headers 禁止携带 " + e.getKey() + "（认证走凭据引用）");
                }
                headers.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        int timeoutMs = intOf(root.get("timeoutMs"), 5000, "timeoutMs");
        if (timeoutMs < TIMEOUT_MIN || timeoutMs > TIMEOUT_MAX) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "timeoutMs 须在 " + TIMEOUT_MIN + "~" + TIMEOUT_MAX + ": " + timeoutMs);
        }

        String credentialRef = root.get("credentialRef") == null ? null
                : strOf(root.get("credentialRef"), "credentialRef");
        if (credentialRef != null && !credentialRefExists) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "credentialRef 引用的凭据不存在: " + credentialRef);
        }

        Map<String, Object> parameterSchema = root.get("parameterSchema") == null ? null
                : asMap(root.get("parameterSchema"), "parameterSchema");
        Map<String, Object> requestTemplate = root.get("requestTemplate") == null ? null
                : asMap(root.get("requestTemplate"), "requestTemplate");
        if ("GET".equals(method) && requestTemplate != null && !requestTemplate.isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "GET 不支持 requestTemplate 请求体");
        }

        int maxAttempts = 1;
        int backoffMs = 500;
        if (root.get("retry") != null) {
            Map<String, Object> retry = asMap(root.get("retry"), "retry");
            maxAttempts = intOf(retry.get("maxAttempts"), 1, "retry.maxAttempts");
            backoffMs = intOf(retry.get("backoffMs"), 500, "retry.backoffMs");
            if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_MAX) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "retry.maxAttempts 须在 1~" + MAX_ATTEMPTS_MAX + ": " + maxAttempts);
            }
            if (backoffMs < 0 || backoffMs > BACKOFF_MAX) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "retry.backoffMs 须在 0~" + BACKOFF_MAX + ": " + backoffMs);
            }
        }

        checkPlaceholders(url, headers, requestTemplate, parameterSchema);
        return new HttpRestSpec(schemaVersion, method, url, headers, timeoutMs,
                credentialRef, parameterSchema, requestTemplate, new HttpRestSpec.Retry(maxAttempts, backoffMs));
    }

    /** 解析并校验 JDBC 只读 spec；任何非法输入抛 CONN_SPEC_INVALID。 */
    public static JdbcReadonlySpec parseJdbcReadonly(Object specNode, ObjectMapper mapper,
                                                     boolean passwordRefExists) {
        Map<String, Object> root = asMap(specNode, "spec");
        int schemaVersion = intOf(root.get("schemaVersion"), 0, "schemaVersion");
        if (schemaVersion != 1) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "schemaVersion 仅支持 1");
        }

        Map<String, Object> datasource = root.get("datasource") == null ? null
                : asMap(root.get("datasource"), "datasource");
        if (datasource == null) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "datasource 不能为空");
        }
        String jdbcUrl = strOf(datasource.get("jdbcUrl"), "datasource.jdbcUrl").toLowerCase();
        if (JDBC_URL_PREFIXES.stream().noneMatch(jdbcUrl::startsWith)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "jdbcUrl 仅支持 postgresql/mysql: " + jdbcUrl);
        }
        String username = strOf(datasource.get("username"), "datasource.username");

        String passwordRef = root.get("passwordRef") == null ? null
                : strOf(root.get("passwordRef"), "passwordRef");
        if (passwordRef != null && !passwordRefExists) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "passwordRef 引用的凭据不存在: " + passwordRef);
        }

        Object tablesNode = root.get("allowedTables");
        if (!(tablesNode instanceof List<?>) || ((List<?>) tablesNode).isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "allowedTables 不能为空");
        }
        List<String> allowedTables = ((List<?>) tablesNode).stream()
                .map(t -> {
                    String table = String.valueOf(t).toLowerCase();
                    if (!table.matches("^[a-z][a-z0-9_]{0,62}$")) {
                        throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                                "allowedTables 表名非法: " + table);
                    }
                    return table;
                }).toList();

        String sqlTemplate = strOf(root.get("sqlTemplate"), "sqlTemplate");
        checkReadonlySql(sqlTemplate, allowedTables);

        Map<String, Object> parameterSchema = root.get("parameterSchema") == null ? null
                : asMap(root.get("parameterSchema"), "parameterSchema");
        checkNamedParams(sqlTemplate, parameterSchema);

        int maxRows = intOf(root.get("maxRows"), JDBC_MAX_ROWS_DEFAULT, "maxRows");
        if (maxRows < 1 || maxRows > JDBC_MAX_ROWS_MAX) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "maxRows 须在 1~" + JDBC_MAX_ROWS_MAX + ": " + maxRows);
        }
        int timeoutMs = intOf(root.get("timeoutMs"), 5000, "timeoutMs");
        if (timeoutMs < TIMEOUT_MIN || timeoutMs > TIMEOUT_MAX) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "timeoutMs 须在 " + TIMEOUT_MIN + "~" + TIMEOUT_MAX + ": " + timeoutMs);
        }
        return new JdbcReadonlySpec(schemaVersion, jdbcUrl, username, passwordRef,
                allowedTables, sqlTemplate, parameterSchema, maxRows, timeoutMs);
    }

    /** 只读 SQL 静态校验：单语句 SELECT(/WITH) 开头、无写关键字、引用表 ⊆ 白名单。 */
    static void checkReadonlySql(String sql, List<String> allowedTables) {
        String trimmed = sql.strip().replace('\n', ' ');
        if (trimmed.contains(";")) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "sqlTemplate 不允许多语句");
        }
        String lower = trimmed.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "sqlTemplate 仅允许 SELECT/WITH 查询");
        }
        Matcher write = JDBC_WRITE_KEYWORD.matcher(trimmed);
        if (write.find()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "sqlTemplate 含写关键字: " + write.group());
        }
        // FROM/JOIN 后的表名必须命中白名单（词边界；MVP 静态近似，连接层只读兜底）
        Matcher tableRef = Pattern.compile("\\b(from|join)\\s+([a-z_][a-z0-9_.]*)",
                Pattern.CASE_INSENSITIVE).matcher(trimmed);
        while (tableRef.find()) {
            String table = tableRef.group(2).contains(".")
                    ? tableRef.group(2).substring(tableRef.group(2).lastIndexOf('.') + 1)
                    : tableRef.group(2);
            if (!allowedTables.contains(table)) {
                throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                        "sqlTemplate 引用表不在 allowedTables 内: " + table);
            }
        }
    }

    /** :name 命名参数必须已声明（有占位即须有 parameterSchema）。 */
    static void checkNamedParams(String sql, Map<String, Object> parameterSchema) {
        Set<String> used = new HashSet<>();
        Matcher m = NAMED_PARAM.matcher(sql);
        while (m.find()) {
            used.add(m.group(1));
        }
        if (used.isEmpty()) {
            return;
        }
        if (parameterSchema == null) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "使用了 :param 命名参数但未声明 parameterSchema: " + used);
        }
        Set<String> declared = declaredParams(parameterSchema);
        List<String> undeclared = used.stream().filter(p -> !declared.contains(p)).sorted().toList();
        if (!undeclared.isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "命名参数未在 parameterSchema.properties 中声明: " + undeclared);
        }
    }

    /** 校验必填参数齐备（缺参抛 1000；执行前失败，不发出半渲染请求）。 */
    public static void checkRequiredParams(Map<String, Object> parameterSchema, Map<String, Object> params) {
        if (parameterSchema == null) {
            return;
        }
        List<String> missing = requiredParams(parameterSchema).stream()
                .filter(n -> params == null || !params.containsKey(n) || params.get(n) == null)
                .sorted().toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少必填参数: " + missing);
        }
    }

    /** parameterSchema.properties 声明的参数名集合（未声明 schema 返回空集）。 */
    public static Set<String> declaredParams(Map<String, Object> parameterSchema) {
        if (parameterSchema == null) {
            return Set.of();
        }
        Object properties = parameterSchema.get("properties");
        if (properties instanceof Map<?, ?> props) {
            Set<String> names = new HashSet<>();
            props.keySet().forEach(k -> names.add(String.valueOf(k)));
            return names;
        }
        return Set.of();
    }

    /** parameterSchema.required 声明的必填参数名集合。 */
    public static Set<String> requiredParams(Map<String, Object> parameterSchema) {
        if (parameterSchema == null || !(parameterSchema.get("required") instanceof List<?> list)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        list.forEach(r -> names.add(String.valueOf(r)));
        return names;
    }

    /** 收集 spec 中使用的 {{param}} 占位符。 */
    public static Set<String> usedPlaceholders(String url, Map<String, String> headers,
                                               Map<String, Object> template) {
        Set<String> used = new HashSet<>();
        collectIn(url, used);
        if (headers != null) {
            headers.values().forEach(v -> collectIn(v, used));
        }
        collectInObject(template, used);
        return used;
    }

    private static void checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "url 无法解析: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "url 仅支持 http/https: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, "url 缺少 host: " + url);
        }
    }

    /** 占位符闭包：使用的参数必须已声明（声明了 schema 才约束）；防渲染期 NameError。 */
    private static void checkPlaceholders(String url, Map<String, String> headers,
                                          Map<String, Object> template, Map<String, Object> parameterSchema) {
        Set<String> used = usedPlaceholders(url, headers, template);
        if (used.isEmpty()) {
            return;
        }
        if (parameterSchema == null) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "使用了 {{param}} 占位符但未声明 parameterSchema: " + used);
        }
        Set<String> declared = declaredParams(parameterSchema);
        List<String> undeclared = used.stream().filter(p -> !declared.contains(p)).sorted().toList();
        if (!undeclared.isEmpty()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID,
                    "占位符未在 parameterSchema.properties 中声明: " + undeclared);
        }
    }

    private static void collectInObject(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectInObject(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectInObject(v, out));
        } else if (node instanceof String s) {
            collectIn(s, out);
        }
    }

    private static void collectIn(String text, Set<String> out) {
        if (text == null) {
            return;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private static Map<String, Object> asMap(Object node, String field) {
        if (!(node instanceof Map)) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, field + " 须为对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) node;
        return map;
    }

    private static String strOf(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, field + " 不能为空");
        }
        return String.valueOf(value);
    }

    private static int intOf(Object value, int fallback, String field) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.CONN_SPEC_INVALID, field + " 须为整数: " + value);
        }
    }
}

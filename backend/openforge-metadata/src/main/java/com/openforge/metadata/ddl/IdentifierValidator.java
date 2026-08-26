package com.openforge.metadata.ddl;

import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 标识符白名单校验（F2 设计 4 安全红线）：
 * objectKey/fieldKey 必须匹配 ^[a-z][a-z0-9_]{2,40}$ 且非 SQL 保留字；
 * fieldKey 额外不得与动态表标准列重名。校验通过后 DDL 生成器方可拼接标识符。
 */
public final class IdentifierValidator {

    /** 小写字母开头，仅小写字母/数字/下划线，长度 3~41。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,40}$");

    /** 动态表自动附加的标准列，自定义字段不得占用。 */
    public static final Set<String> STANDARD_COLUMNS = Set.of(
            "id", "tenant_id", "created_by", "created_at", "updated_by", "updated_at", "deleted");

    /** SQL/PostgreSQL 保留字与危险关键字黑名单（小写）。宁误杀不漏放。 */
    private static final Set<String> RESERVED_WORDS = Set.of(
            // 保留关键字
            "all", "and", "any", "array", "as", "asc", "asymmetric", "authorization", "binary",
            "both", "case", "cast", "check", "collate", "column", "constraint", "create",
            "cross", "current_catalog", "current_date", "current_role", "current_time",
            "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "false", "fetch", "for", "foreign", "freeze",
            "from", "full", "grant", "group", "having", "ilike", "in", "initially", "inner",
            "intersect", "into", "is", "isnull", "join", "lateral", "leading", "left", "like",
            "limit", "localtime", "localtimestamp", "natural", "not", "notnull", "null",
            "offset", "on", "only", "or", "order", "outer", "overlaps", "placing", "primary",
            "references", "returning", "right", "select", "session_user", "similar", "some",
            "symmetric", "table", "then", "to", "trailing", "true", "union", "unique", "user",
            "using", "variadic", "verbose", "when", "where", "window", "with",
            // 非保留但拼入 DDL/查询易出歧义或被注入利用的关键字
            "alter", "analyze", "between", "bigint", "boolean", "by", "cascade",
            "comment", "commit", "database", "delete", "drop", "exists", "explain",
            "index", "insert", "numeric", "password", "pg_sleep", "revoke", "rollback",
            "schema", "set", "tablespace", "truncate", "update", "values", "view", "vacuum");

    private IdentifierValidator() {
    }

    /** objectKey 白名单校验（同时决定表名后缀与 API 路径段），非法抛 1000。 */
    public static void checkObjectKey(String objectKey) {
        checkKey(objectKey, "objectKey");
    }

    /** fieldKey 白名单校验（= 动态表列名），额外排除标准列，非法抛 1000。 */
    public static void checkFieldKey(String fieldKey) {
        if (fieldKey != null && STANDARD_COLUMNS.contains(fieldKey)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "fieldKey 与动态表标准列重名: " + fieldKey + "（标准列: " + STANDARD_COLUMNS + "）");
        }
        checkKey(fieldKey, "fieldKey");
    }

    private static void checkKey(String key, String label) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    label + " 非法: " + key + "（须匹配 ^[a-z][a-z0-9_]{2,40}$）");
        }
        if (RESERVED_WORDS.contains(key.toLowerCase(Locale.ROOT))) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 为 SQL 保留字: " + key);
        }
    }
}

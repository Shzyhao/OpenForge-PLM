package com.openforge.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.common.tenant.TenantContext;
import com.openforge.metadata.ddl.DdlGenerator;
import com.openforge.metadata.ddl.FieldType;
import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.mapper.MetaFieldMapper;
import com.openforge.metadata.mapper.MetaObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态对象 CRUD 运行时（F2 设计 3/4）：发布后的对象即刻获得通用记录接口。
 * 值一律参数化绑定；标识符一律来自已白名单校验的元数据（表名/列名不含用户输入）。
 * 过滤仅支持白名单字段的 eq/like/in（like 仅 STRING），排序字段同受白名单约束。
 */
@Service
@RequiredArgsConstructor
public class DynamicRecordService {

    /** 排序允许的标准列（字段列天然在元数据白名单内）。 */
    private static final Set<String> SORTABLE_STANDARD_COLUMNS = Set.of("id", "created_at", "updated_at");

    private final MetaObjectMapper metaObjectMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final JdbcTemplate jdbcTemplate;

    // ===== 创建 =====

    public Map<String, Object> create(String objectKey, Map<String, Object> body, Long userId) {
        PublishedMeta meta = loadPublished(objectKey);
        Map<String, Object> values = new LinkedHashMap<>();
        for (MetaField field : meta.fields) {
            Object raw = body.get(field.getFieldKey());
            if (raw == null) {
                if (field.getRequired() != null && field.getRequired() == 1) {
                    throw new BizException(ErrorCode.INVALID_ARGUMENT, "缺少必填字段: " + field.getFieldKey());
                }
                continue;
            }
            values.put(field.getFieldKey(), coerce(field, raw));
        }
        if (values.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "至少提供一个字段");
        }
        rejectUnknownFields(meta, body);

        String columns = String.join(", ", values.keySet()) + ", tenant_id, created_by";
        String placeholders = values.keySet().stream().map(k -> "?").collect(Collectors.joining(", "))
                + ", ?, ?";
        List<Object> params = new ArrayList<>(values.values());
        params.add(TenantContext.getTenantId());
        params.add(userId);
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        String sql = "INSERT INTO " + meta.object.getTableName() + " (" + columns + ") VALUES (" + placeholders + ")";
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "创建记录失败");
        }
        return detail(objectKey, key.longValue());
    }

    // ===== 查询 =====

    public Map<String, Object> detail(String objectKey, Long id) {
        PublishedMeta meta = loadPublished(objectKey);
        return queryOne(meta, "id = ? AND tenant_id = ? AND deleted = 0", id, TenantContext.getTenantId())
                .orElseThrow(() -> new BizException(ErrorCode.META_RECORD_NOT_FOUND));
    }

    public Page<Map<String, Object>> page(String objectKey, long page, long pageSize,
                                          List<String> filters, String sort) {
        PublishedMeta meta = loadPublished(objectKey);
        List<FilterCondition> conditions = filters.stream().map(f -> parseFilter(meta, f)).toList();
        WhereClause where = buildWhere(conditions);
        String orderBy = buildOrderBy(meta, sort);

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + meta.object.getTableName()
                        + " WHERE tenant_id = ? AND deleted = 0" + where.sql(),
                Long.class, tenantParams(where).toArray());

        List<Object> params = new ArrayList<>(tenantParams(where));
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + selectColumns(meta) + " FROM " + meta.object.getTableName()
                        + " WHERE tenant_id = ? AND deleted = 0" + where.sql()
                        + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                params.toArray());
        return new Page<>(total == null ? 0 : total, page, pageSize, rows.stream().map(this::convertRow).toList());
    }

    // ===== 更新 / 软删 =====

    public Map<String, Object> update(String objectKey, Long id, Map<String, Object> body, Long userId) {
        PublishedMeta meta = loadPublished(objectKey);
        rejectUnknownFields(meta, body);
        Map<String, Object> values = new LinkedHashMap<>();
        for (MetaField field : meta.fields) {
            Object raw = body.get(field.getFieldKey());
            if (raw != null) {
                values.put(field.getFieldKey(), coerce(field, raw));
            }
        }
        if (values.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "至少提供一个字段");
        }
        String setClause = values.keySet().stream().map(k -> k + " = ?")
                .collect(Collectors.joining(", ")) + ", updated_by = ?, updated_at = CURRENT_TIMESTAMP";
        List<Object> params = new ArrayList<>(values.values());
        params.add(userId);
        params.add(TenantContext.getTenantId());
        params.add(id);
        int affected = jdbcTemplate.update("UPDATE " + meta.object.getTableName() + " SET " + setClause
                + " WHERE tenant_id = ? AND id = ? AND deleted = 0", params.toArray());
        if (affected == 0) {
            throw new BizException(ErrorCode.META_RECORD_NOT_FOUND);
        }
        return detail(objectKey, id);
    }

    public void delete(String objectKey, Long id, Long userId) {
        PublishedMeta meta = loadPublished(objectKey);
        int affected = jdbcTemplate.update(
                "UPDATE " + meta.object.getTableName()
                        + " SET deleted = 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                userId, id, TenantContext.getTenantId());
        if (affected == 0) {
            throw new BizException(ErrorCode.META_RECORD_NOT_FOUND);
        }
    }

    // ===== 元数据装载 =====

    private PublishedMeta loadPublished(String objectKey) {
        MetaObject object = metaObjectMapper.selectOne(
                new LambdaQueryWrapper<MetaObject>().eq(MetaObject::getObjectKey, objectKey));
        if (object == null) {
            throw new BizException(ErrorCode.META_OBJECT_NOT_FOUND);
        }
        if (!"PUBLISHED".equals(object.getStatus())) {
            throw new BizException(ErrorCode.META_OBJECT_NOT_PUBLISHED);
        }
        List<MetaField> fields = metaFieldMapper.selectList(
                new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, object.getId())
                        .orderByAsc(MetaField::getSortOrder));
        return new PublishedMeta(object, fields);
    }

    private record PublishedMeta(MetaObject object, List<MetaField> fields) {
    }

    private void rejectUnknownFields(PublishedMeta meta, Map<String, Object> body) {
        Set<String> known = meta.fields().stream().map(MetaField::getFieldKey).collect(Collectors.toSet());
        List<String> unknown = body.keySet().stream().filter(k -> !known.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "未知字段: " + unknown);
        }
    }

    private java.util.Optional<Map<String, Object>> queryOne(PublishedMeta meta, String condition, Object... params) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + selectColumns(meta) + " FROM " + meta.object.getTableName()
                        + " WHERE " + condition, params);
        return rows.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(convertRow(rows.get(0)));
    }

    private String selectColumns(PublishedMeta meta) {
        String fieldColumns = meta.fields().stream().map(MetaField::getFieldKey).collect(Collectors.joining(", "));
        return "id" + (fieldColumns.isEmpty() ? "" : ", " + fieldColumns)
                + ", created_by, created_at, updated_by, updated_at";
    }

    /** Timestamp → LocalDateTime（ISO 序列化），其余原样。 */
    private Map<String, Object> convertRow(Map<String, Object> row) {
        Map<String, Object> converted = new LinkedHashMap<>();
        row.forEach((k, v) -> converted.put(k, v instanceof Timestamp t ? t.toLocalDateTime() : v));
        return converted;
    }

    // ===== 过滤与排序（白名单 + 参数化，F2 设计 4） =====

    private record WhereClause(String sql, List<Object> params) {
    }

    /** 租户参数前置（动态表均含 tenant_id 标准列，隔离优先于过滤条件）。 */
    private List<Object> tenantParams(WhereClause where) {
        List<Object> params = new ArrayList<>();
        params.add(TenantContext.getTenantId());
        params.addAll(where.params());
        return params;
    }

    private record FilterCondition(String column, String op, List<Object> values) {
    }

    /** 解析 `field:op:value`（in 的 value 为逗号分隔多值），字段/操作符双白名单。 */
    private FilterCondition parseFilter(PublishedMeta meta, String filter) {
        String[] parts = filter.split(":", 3);
        if (parts.length != 3) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "filter 格式须为 field:op:value: " + filter);
        }
        MetaField field = meta.fields().stream()
                .filter(f -> f.getFieldKey().equals(parts[0])).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_ARGUMENT, "filter 字段不在白名单: " + parts[0]));
        String op = parts[1];
        if (!op.equals("eq") && !op.equals("like") && !op.equals("in")) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "filter 操作符仅支持 eq/like/in: " + op);
        }
        if (op.equals("like") && FieldType.parse(field.getFieldType()) != FieldType.STRING) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "like 仅支持 STRING 字段: " + field.getFieldKey());
        }
        List<Object> values = new ArrayList<>();
        for (String raw : op.equals("in") ? parts[2].split(",") : new String[]{parts[2]}) {
            values.add(coerce(field, raw));
        }
        return new FilterCondition(field.getFieldKey(), op, values);
    }

    private WhereClause buildWhere(List<FilterCondition> conditions) {
        if (conditions.isEmpty()) {
            return new WhereClause("", List.of());
        }
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (FilterCondition c : conditions) {
            sql.append(" AND ").append(c.column()).append(' ');
            switch (c.op()) {
                case "eq" -> {
                    sql.append("= ?");
                    params.add(c.values().get(0));
                }
                case "like" -> {
                    sql.append("LIKE ?");
                    params.add("%" + c.values().get(0) + "%");
                }
                case "in" -> {
                    sql.append("IN (").append(c.values().stream().map(v -> "?")
                            .collect(Collectors.joining(", "))).append(')');
                    params.addAll(c.values());
                }
                default -> throw new BizException(ErrorCode.INVALID_ARGUMENT, "filter 操作符非法: " + c.op());
            }
        }
        return new WhereClause(sql.toString(), params);
    }

    /** 排序字段白名单：元数据字段 + id/created_at/updated_at；`-` 前缀降序。 */
    private String buildOrderBy(PublishedMeta meta, String sort) {
        if (sort == null || sort.isBlank()) {
            return "id DESC";
        }
        boolean desc = sort.startsWith("-");
        String column = desc ? sort.substring(1) : sort;
        boolean known = SORTABLE_STANDARD_COLUMNS.contains(column)
                || meta.fields().stream().anyMatch(f -> f.getFieldKey().equals(column));
        if (!known) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "sort 字段不在白名单: " + column);
        }
        return column + (desc ? " DESC" : " ASC");
    }

    // ===== 值校验与类型收敛（F2 设计 2 类型映射的运行时面） =====

    private Object coerce(MetaField field, Object raw) {
        FieldType type = FieldType.parse(field.getFieldType());
        String label = field.getFieldKey();
        return switch (type) {
            case STRING -> coerceString(field, raw, label);
            case NUMBER -> coerceNumber(raw, label);
            case DATE -> coerceDate(raw, label);
            case BOOLEAN -> coerceBoolean(raw, label);
            case REFERENCE -> checkReferenceExists(field, coerceReference(raw, label));
        };
    }

    private Object coerceString(MetaField field, Object raw, String label) {
        if (!(raw instanceof String s)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 须为字符串");
        }
        if (field.getMaxLength() != null && s.length() > field.getMaxLength()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    label + " 超长: " + s.length() + " > " + field.getMaxLength());
        }
        return s;
    }

    private Object coerceNumber(Object raw, String label) {
        if (raw instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (raw instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 须为数字");
    }

    private Object coerceDate(Object raw, String label) {
        if (raw instanceof String s) {
            try {
                return Timestamp.valueOf(LocalDateTime.parse(s));
            } catch (DateTimeParseException ignored) {
                try {
                    return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
                } catch (DateTimeParseException e) {
                    // fall through
                }
            }
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 须为 ISO 日期时间（yyyy-MM-dd[THH:mm:ss]）");
    }

    private Object coerceBoolean(Object raw, String label) {
        if (raw instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (raw instanceof Number n && (n.intValue() == 0 || n.intValue() == 1)) {
            return n.intValue();
        }
        if (raw instanceof String s) {   // filter 参数值为字符串
            if (s.equalsIgnoreCase("true") || s.equals("1")) {
                return 1;
            }
            if (s.equalsIgnoreCase("false") || s.equals("0")) {
                return 0;
            }
        }
        throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 须为布尔值");
    }

    private Long coerceReference(Object raw, String label) {
        Long id = null;
        if (raw instanceof Number n) {
            id = n.longValue();
        } else if (raw instanceof String s) {
            try {
                id = Long.valueOf(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (id == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, label + " 须为被引记录 id");
        }
        return id;
    }

    /** REFERENCE 存在性（F2 设计 3）：被引对象发布期已保证 PUBLISHED，此处查其动态表。 */
    private Object checkReferenceExists(MetaField field, Long refId) {
        String refTable = DdlGenerator.tableNameOf(field.getRefObject());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + refTable + " WHERE id = ? AND deleted = 0", Long.class, refId);
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    field.getFieldKey() + " 引用的记录不存在: " + field.getRefObject() + "#" + refId);
        }
        return refId;
    }

    /** 通用分页结果（避免与 mybatis-plus 的 Page 重名混淆，仅作传输结构）。 */
    public record Page<T>(long total, long page, long pageSize, List<T> items) {
    }
}

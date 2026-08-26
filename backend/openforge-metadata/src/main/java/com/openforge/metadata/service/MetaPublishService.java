package com.openforge.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.metadata.ddl.DdlGenerator;
import com.openforge.metadata.ddl.FieldType;
import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.entity.MetaObjectVersion;
import com.openforge.metadata.mapper.MetaFieldMapper;
import com.openforge.metadata.mapper.MetaObjectMapper;
import com.openforge.metadata.mapper.MetaObjectVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 发布流水线（F2 设计 5）。F2-2 覆盖：校验 → 生成 DDL → 安全门校验 → 执行 →
 * 写版本快照 → 状态 PUBLISHED/version+1。
 * 权限点创建（auth /internal）与 Schema 知识同步（knowledge /internal）随 F2-3 接入。
 */
@Service
@RequiredArgsConstructor
public class MetaPublishService {

    /**
     * DDL 安全门（F2 设计 4）：只放行幂等增量的 dyn_ 前缀语句。
     * 生成器虽已保证语句形态，执行前再独立校验一次（纵深防御，防未来生成器演化引入破坏性语句）。
     */
    private static final Pattern ALLOWED_DDL = Pattern.compile(
            "^(CREATE TABLE IF NOT EXISTS dyn_[a-z0-9_]+ \\("
                    + "|CREATE INDEX IF NOT EXISTS idx_dyn_[a-z0-9_]+ ON dyn_[a-z0-9_]+ "
                    + "|ALTER TABLE dyn_[a-z0-9_]+ ADD COLUMN IF NOT EXISTS )");

    private final MetaObjectMapper metaObjectMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaObjectVersionMapper metaObjectVersionMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> publish(Long objectId, Long userId) {
        MetaObject object = metaObjectMapper.selectById(objectId);
        if (object == null) {
            throw new BizException(ErrorCode.META_OBJECT_NOT_FOUND);
        }
        if (!"DRAFT".equals(object.getStatus())) {
            throw new BizException(ErrorCode.META_OBJECT_PUBLISHED, "对象已发布，重复发布走新版本流程");
        }
        List<MetaField> fields = metaFieldMapper.selectList(
                new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, objectId)
                        .orderByAsc(MetaField::getSortOrder));
        if (fields.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "至少需要一个字段");
        }
        checkReferenceClosure(object, fields);

        // 首次发布建表；表已存在时 CREATE 为空操作、字段逐列 ADD COLUMN IF NOT EXISTS 追加
        String createDdl = DdlGenerator.createTableSql(object, fields);
        List<String> allStatements = new ArrayList<>();
        allStatements.add(createDdl);
        allStatements.addAll(DdlGenerator.addColumnSqls(object.getObjectKey(), fields));
        allStatements.forEach(this::guardAndExecute);

        MetaObjectVersion snapshot = new MetaObjectVersion();
        snapshot.setObjectId(objectId);
        snapshot.setVersion(object.getVersion());
        snapshot.setDefinition(toDefinitionJson(object, fields));
        snapshot.setDdlText(String.join(";\n", allStatements));
        snapshot.setPublishedBy(userId);
        metaObjectVersionMapper.insert(snapshot);

        object.setStatus("PUBLISHED");
        object.setVersion(object.getVersion() + 1);
        metaObjectMapper.updateById(object);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectId", objectId);
        result.put("objectKey", object.getObjectKey());
        result.put("tableName", object.getTableName());
        result.put("status", object.getStatus());
        result.put("version", snapshot.getVersion());
        return result;
    }

    /** 发布期引用闭合（F2 设计 5）：REFERENCE 指向的对象必须已发布（自身除外，随本次发布）。 */
    private void checkReferenceClosure(MetaObject self, List<MetaField> fields) {
        for (MetaField field : fields) {
            if (FieldType.parse(field.getFieldType()) != FieldType.REFERENCE) {
                continue;
            }
            String refObject = field.getRefObject();
            if (refObject == null || refObject.isBlank()) {
                throw new BizException(ErrorCode.META_FIELD_INVALID,
                        "REFERENCE 字段缺少 refObject: " + field.getFieldKey());
            }
            if (refObject.equals(self.getObjectKey())) {
                continue;
            }
            MetaObject target = metaObjectMapper.selectOne(
                    new LambdaQueryWrapper<MetaObject>().eq(MetaObject::getObjectKey, refObject));
            if (target == null || !"PUBLISHED".equals(target.getStatus())) {
                throw new BizException(ErrorCode.META_REF_OBJECT_NOT_FOUND,
                        "引用对象未发布，先发布被引对象: " + refObject + "（字段 " + field.getFieldKey() + "）");
            }
        }
    }

    private void guardAndExecute(String statement) {
        String normalized = statement.strip();
        if (!ALLOWED_DDL.matcher(normalized).find()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "DDL 安全门拒绝执行语句");
        }
        jdbcTemplate.execute(normalized);
    }

    private String toDefinitionJson(MetaObject object, List<MetaField> fields) {
        try {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("objectKey", object.getObjectKey());
            definition.put("displayName", object.getDisplayName());
            definition.put("tableName", object.getTableName());
            definition.put("fields", fields);
            return objectMapper.writeValueAsString(definition);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "定义快照序列化失败");
        }
    }
}

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
 * 发布流水线（F2 设计 5）：校验 → 生成 DDL → 安全门校验 → 执行 →
 * 写版本快照 → 创建权限点 ×4（auth /internal，失败阻断）→ Schema 知识同步
 * （knowledge /internal/items，尽力而为）→ AI 网关表登记（/internal/tables，尽力而为）
 * → 状态 PUBLISHED/version+1。
 */
@Service
@RequiredArgsConstructor
public class MetaPublishService {

    /** 发布时自动创建的四权限点（F2 设计 4：{objectKey}:view/create/update/delete）。 */
    private record PermissionAction(String code, String label) {
    }

    private static final List<PermissionAction> PERMISSION_ACTIONS = List.of(
            new PermissionAction("view", "查看"), new PermissionAction("create", "创建"),
            new PermissionAction("update", "更新"), new PermissionAction("delete", "删除"));

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
    private final com.openforge.metadata.client.PublishPipelineClients pipelineClients;
    private final PublishedMetaCache publishedMetaCache;

    /** EXTENSION 模块的服务地址=本服务（动态 CRUD 由 metadata 承载），随部署覆盖。 */
    @org.springframework.beans.factory.annotation.Value("${openforge.module.service-uri:http://localhost:8088}")
    private String selfServiceUri;

    private final com.openforge.common.event.EventPublisher eventPublisher;

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

        // 权限点 ×4（失败阻断发布——动态 CRUD 授权的前提）；ADMINS 默认全量管理（V14 起角色名）
        String permPrefix = object.getObjectKey() + ":";
        for (PermissionAction action : PERMISSION_ACTIONS) {
            pipelineClients.ensurePermission(permPrefix + action.code(),
                    object.getDisplayName() + "-" + action.label(), List.of("ADMINS"));
        }
        // Schema 知识同步走事件优先（B2-2：EVENT_ENABLED=true 经 MQ，消费端幂等/租户/链路齐全）；
        // MQ 关闭或发送失败回退既有同步 HTTP（与 v1.3.0 行为一致）。AI 表登记保持同步直连（设计 §3.4）。
        // 事件在事务提交后发出（消费端依赖信封自包含，不回读本表；此处仍按设计延后到 afterCommit）。
        String schemaDescription = schemaDescription(object, fields);
        String schemaTitle = "动态对象表结构：" + object.getDisplayName() + "（" + object.getTableName() + "）";
        String schemaRef = object.getObjectKey();
        String tableName = object.getTableName();
        int schemaVersion = snapshot.getVersion();
        // P2 outbox：publish 在事务内调用——outbox 行随业务原子落库，afterCommit 发送失败由 relay 补发
        boolean viaMq = eventPublisher.publish("openforge-meta", "schema.migrated", Map.of(
                "objectKey", schemaRef,
                "displayName", object.getDisplayName(),
                "tableName", tableName,
                "version", schemaVersion,
                "description", schemaDescription));
        publishAfterCommit(() -> {
            if (!viaMq) {
                // MQ 关闭：回退既有同步 HTTP（与 v1.3.0 行为一致）
                pipelineClients.syncSchemaItem(schemaTitle, schemaDescription, schemaRef);
            }
            pipelineClients.registerAiTable(tableName, schemaDescription);
        });

        // A4-4：发布即注册 EXTENSION 模块——路由/菜单/模块管理与原生服务同构
        // （与权限点同语义：失败阻断发布，auth 不可用时两者本就同命运）
        pipelineClients.registerExtensionModule(object.getId(), object.getObjectKey(),
                object.getDisplayName(), snapshot.getVersion(), selfServiceUri);

        object.setStatus("PUBLISHED");
        object.setVersion(object.getVersion() + 1);
        metaObjectMapper.updateById(object);

        // 缓存驱逐在事务 afterCommit：提交前驱逐会让并发请求把旧数据重新回填 TTL 窗口
        String objectKey = object.getObjectKey();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publishedMetaCache.evict(objectKey);
                        }
                    });
        } else {
            publishedMetaCache.evict(objectKey);
        }

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

    /** 事务提交后执行（B2 设计 3.3：消费者不可见未提交状态）。 */
    private void publishAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    /** 表/列级业务描述：knowledge 知识条目与 AI 网关 nl2sql Prompt 共用同一份。 */
    private String schemaDescription(MetaObject object, List<MetaField> fields) {
        StringBuilder sb = new StringBuilder(object.getDisplayName())
                .append("（").append(object.getTableName()).append("）：");
        for (MetaField field : fields) {
            sb.append(field.getFieldKey()).append(' ').append(field.getDisplayName())
                    .append('(').append(field.getFieldType());
            if (field.getRequired() != null && field.getRequired() == 1) {
                sb.append(",必填");
            }
            if (field.getRefObject() != null && !field.getRefObject().isBlank()) {
                sb.append(",引用 ").append(field.getRefObject());
            }
            sb.append("), ");
        }
        String description = sb.substring(0, sb.length() - 2);
        // 性能护栏（画像 §4 事件总线画像）：事件 payload KB 级——字段极多的对象截断描述
        return description.length() > 2000 ? description.substring(0, 2000) + "..." : description;
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

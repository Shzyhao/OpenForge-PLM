package com.openforge.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.metadata.ddl.DdlGenerator;
import com.openforge.metadata.ddl.FieldType;
import com.openforge.metadata.ddl.IdentifierValidator;
import com.openforge.metadata.dto.CreateMetaObjectRequest;
import com.openforge.metadata.dto.DdlPreviewResponse;
import com.openforge.metadata.dto.MetaFieldRequest;
import com.openforge.metadata.dto.MetaObjectDetailResponse;
import com.openforge.metadata.dto.MetaObjectSummaryResponse;
import com.openforge.metadata.dto.PageResponse;
import com.openforge.metadata.dto.UpdateMetaObjectRequest;
import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.mapper.MetaFieldMapper;
import com.openforge.metadata.mapper.MetaObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元对象建模服务（F2 设计 3/4）：建模 CRUD + 白名单校验 + DDL 预览。
 * 发布流水线（DDL 执行/权限点/知识同步）随 F2-2/F2-3 落地。
 */
@Service
@RequiredArgsConstructor
public class MetaObjectService {

    private final MetaObjectMapper metaObjectMapper;
    private final MetaFieldMapper metaFieldMapper;

    /** 建模：校验 objectKey/字段白名单 + 引用闭合，落库为 DRAFT。 */
    @Transactional
    public MetaObjectDetailResponse create(CreateMetaObjectRequest request, Long userId) {
        IdentifierValidator.checkObjectKey(request.getObjectKey());
        Long existed = metaObjectMapper.selectCount(
                new LambdaQueryWrapper<MetaObject>().eq(MetaObject::getObjectKey, request.getObjectKey()));
        if (existed > 0) {
            throw new BizException(ErrorCode.META_OBJECT_KEY_EXISTS);
        }

        MetaObject object = new MetaObject();
        object.setObjectKey(request.getObjectKey());
        object.setDisplayName(request.getDisplayName());
        object.setTableName(DdlGenerator.tableNameOf(request.getObjectKey()));
        object.setStatus("DRAFT");
        object.setVersion(1);
        object.setTenantId(com.openforge.common.tenant.TenantContext.getTenantId());
        object.setCreatedBy(userId);
        metaObjectMapper.insert(object);

        replaceFields(object.getId(), request.getObjectKey(), request.getFields());
        return detail(object.getId());
    }

    /** 草稿可改（全量替换字段；objectKey 不可变）。已发布对象走新版本流程（F2-3）。 */
    @Transactional
    public MetaObjectDetailResponse update(Long id, UpdateMetaObjectRequest request) {
        MetaObject object = requireObject(id);
        if (!"DRAFT".equals(object.getStatus())) {
            throw new BizException(ErrorCode.META_OBJECT_PUBLISHED);
        }
        object.setDisplayName(request.getDisplayName());
        metaObjectMapper.updateById(object);

        metaFieldMapper.delete(new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, id));
        replaceFields(id, object.getObjectKey(), request.getFields());
        return detail(id);
    }

    /** 列表：分页 + 各对象字段数。 */
    public PageResponse<MetaObjectSummaryResponse> page(long page, long pageSize) {
        Page<MetaObject> result = metaObjectMapper.selectPage(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<MetaObject>().orderByDesc(MetaObject::getId));
        // 聚合下推数据库（原实现拉全表到内存数数，字段行随建模量线性增长）
        Map<Long, Long> fieldCounts = new java.util.HashMap<>();
        for (Map<String, Object> row : metaFieldMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MetaField>()
                        .select("object_id", "count(*) as cnt").groupBy("object_id"))) {
            fieldCounts.put(Long.valueOf(String.valueOf(row.get("object_id"))),
                    Long.valueOf(String.valueOf(row.get("cnt"))));
        }
        return PageResponse.from(result,
                o -> MetaObjectSummaryResponse.from(o, fieldCounts.getOrDefault(o.getId(), 0L)));
    }

    public MetaObjectDetailResponse detail(Long id) {
        MetaObject object = requireObject(id);
        List<MetaField> fields = metaFieldMapper.selectList(
                new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, id)
                        .orderByAsc(MetaField::getSortOrder));
        return MetaObjectDetailResponse.from(object, fields);
    }

    /** DDL 预览：与发布时执行的语句同源（F2 设计 5），发布前供二次开发者确认。 */
    public DdlPreviewResponse previewDdl(Long id) {
        MetaObject object = requireObject(id);
        List<MetaField> fields = metaFieldMapper.selectList(
                new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, id)
                        .orderByAsc(MetaField::getSortOrder));
        return new DdlPreviewResponse(object.getId(), object.getObjectKey(), object.getTableName(),
                DdlGenerator.createTableSql(object, fields));
    }

    private MetaObject requireObject(Long id) {
        MetaObject object = metaObjectMapper.selectById(id);
        if (object == null) {
            throw new BizException(ErrorCode.META_OBJECT_NOT_FOUND);
        }
        return object;
    }

    /**
     * 字段集校验并写入：fieldKey 白名单/去重、类型映射、REFERENCE 引用闭合
     * （指向已存在对象或自身；refField 须为被引对象的字段或 id）。
     */
    private void replaceFields(Long objectId, String selfKey, List<MetaFieldRequest> fields) {
        Set<String> seen = new HashSet<>();
        int order = 0;
        for (MetaFieldRequest req : fields) {
            if (!seen.add(req.getFieldKey())) {
                throw new BizException(ErrorCode.META_FIELD_INVALID, "字段重复: " + req.getFieldKey());
            }
            IdentifierValidator.checkFieldKey(req.getFieldKey());
            FieldType type = FieldType.parse(req.getFieldType());

            MetaField field = new MetaField();
            field.setObjectId(objectId);
            field.setFieldKey(req.getFieldKey());
            field.setDisplayName(req.getDisplayName());
            field.setFieldType(type.name());
            field.setRequired(Boolean.TRUE.equals(req.getRequired()) ? 1 : 0);
            field.setMaxLength(type == FieldType.STRING ? normalizeWidth(req.getMaxLength()) : null);
            if (type == FieldType.REFERENCE) {
                validateReference(selfKey, req, fields);
                field.setRefObject(req.getRefObject());
                field.setRefField(req.getRefField() == null || req.getRefField().isBlank()
                        ? "id" : req.getRefField());
            }
            field.setSortOrder(order++);
            metaFieldMapper.insert(field);
        }
    }

    private Integer normalizeWidth(Integer maxLength) {
        if (maxLength == null) {
            return null;
        }
        if (maxLength < 1 || maxLength > 4000) {
            throw new BizException(ErrorCode.META_FIELD_INVALID,
                    "maxLength 须在 1~4000 之间: " + maxLength);
        }
        return maxLength;
    }

    /** 引用闭合校验：refObject 必须是已建模对象或自身；refField 须为 id 或被引对象的字段。 */
    private void validateReference(String selfKey, MetaFieldRequest req, List<MetaFieldRequest> currentFields) {
        String refObject = req.getRefObject();
        if (refObject == null || refObject.isBlank()) {
            throw new BizException(ErrorCode.META_FIELD_INVALID,
                    "REFERENCE 字段缺少 refObject: " + req.getFieldKey());
        }
        IdentifierValidator.checkObjectKey(refObject);
        Set<String> refFieldKeys;
        if (refObject.equals(selfKey)) {
            refFieldKeys = currentFields.stream().map(MetaFieldRequest::getFieldKey).collect(Collectors.toSet());
        } else {
            MetaObject target = metaObjectMapper.selectOne(
                    new LambdaQueryWrapper<MetaObject>().eq(MetaObject::getObjectKey, refObject));
            if (target == null) {
                throw new BizException(ErrorCode.META_REF_OBJECT_NOT_FOUND,
                        "refObject 不存在: " + refObject + "（字段 " + req.getFieldKey() + "）");
            }
            refFieldKeys = metaFieldMapper.selectList(
                            new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, target.getId()))
                    .stream().map(MetaField::getFieldKey).collect(Collectors.toSet());
        }
        String refField = req.getRefField();
        if (refField != null && !refField.isBlank() && !"id".equals(refField)
                && !refFieldKeys.contains(refField)) {
            throw new BizException(ErrorCode.META_FIELD_INVALID,
                    "refField 不是被引对象的字段: " + refField + "（字段 " + req.getFieldKey() + "）");
        }
    }
}

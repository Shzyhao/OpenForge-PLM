package com.openforge.metadata.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openforge.common.api.BizException;
import com.openforge.common.api.ErrorCode;
import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaFormLayout;
import com.openforge.metadata.entity.MetaObject;
import com.openforge.metadata.mapper.MetaFieldMapper;
import com.openforge.metadata.mapper.MetaFormLayoutMapper;
import com.openforge.metadata.mapper.MetaObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表单/列表布局服务（F3-2 设计器）：未设计时按字段定义自动派生（全可见、按序），
 * 保存后按布局渲染。字段集与元数据强校验——建模字段变更后旧布局中的未知字段被剔除、
 * 新字段自动补入末尾（设计器制品始终与元数据对齐）。
 */
@Service
@RequiredArgsConstructor
public class MetaLayoutService {

    private static final Set<String> TYPES = Set.of("FORM", "LIST");

    private final MetaObjectMapper metaObjectMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaFormLayoutMapper layoutMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> getLayout(Long objectId, String layoutType) {
        requireType(layoutType);
        List<MetaField> fields = fieldsOf(objectId);
        MetaFormLayout saved = layoutMapper.selectOne(
                new LambdaQueryWrapper<MetaFormLayout>()
                        .eq(MetaFormLayout::getObjectId, objectId)
                        .eq(MetaFormLayout::getLayoutType, layoutType));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectId", objectId);
        result.put("layoutType", layoutType);
        result.put("customized", saved != null);
        result.put("fields", saved == null ? defaultLayout(fields) : reconcile(saved, fields));
        return result;
    }

    public Map<String, Object> saveLayout(Long objectId, String layoutType, List<Map<String, Object>> fields,
                                          Long userId) {
        requireType(layoutType);
        List<MetaField> metaFields = fieldsOf(objectId);
        Set<String> known = metaFields.stream().map(MetaField::getFieldKey).collect(Collectors.toSet());
        List<String> unknown = fields.stream()
                .map(f -> String.valueOf(f.get("fieldKey")))
                .filter(k -> !known.contains(k))
                .toList();
        if (!unknown.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "布局包含未知字段: " + unknown);
        }

        MetaFormLayout saved = layoutMapper.selectOne(
                new LambdaQueryWrapper<MetaFormLayout>()
                        .eq(MetaFormLayout::getObjectId, objectId)
                        .eq(MetaFormLayout::getLayoutType, layoutType));
        MetaFormLayout entity = saved != null ? saved : new MetaFormLayout();
        entity.setObjectId(objectId);
        entity.setLayoutType(layoutType);
        entity.setLayout(toJson(Map.of("fields", reconcileFields(fields, metaFields))));
        entity.setUpdatedBy(userId);
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        if (saved == null) {
            layoutMapper.insert(entity);
        } else {
            layoutMapper.updateById(entity);
        }
        return getLayout(objectId, layoutType);
    }

    /** 布局与元数据对齐：剔除已删字段、按元数据顺序补入新增字段（保留设计的顺序与属性）。 */
    private List<Map<String, Object>> reconcile(MetaFormLayout saved, List<MetaField> fields) {
        try {
            Map<String, Object> json = objectMapper.readValue(saved.getLayout(), Map.class);
            if (json.get("fields") instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> designed = (List<Map<String, Object>>) list;
                return reconcileFields(designed, fields);
            }
        } catch (Exception ignored) {
            // 布局 JSON 损坏 → 回退默认布局
        }
        return defaultLayout(fields);
    }

    private List<Map<String, Object>> reconcileFields(List<Map<String, Object>> designed, List<MetaField> fields) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> entry : designed) {
            byKey.put(String.valueOf(entry.get("fieldKey")), entry);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        // 设计顺序优先，但仅保留仍存在的字段
        for (Map<String, Object> entry : designed) {
            String key = String.valueOf(entry.get("fieldKey"));
            if (fields.stream().anyMatch(f -> f.getFieldKey().equals(key))) {
                result.add(normalize(entry));
            }
        }
        // 建模新增字段自动补入末尾
        for (MetaField field : fields) {
            if (result.stream().noneMatch(e -> e.get("fieldKey").equals(field.getFieldKey()))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fieldKey", field.getFieldKey());
                result.add(normalize(entry));
            }
        }
        return result;
    }

    private Map<String, Object> normalize(Map<String, Object> entry) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("fieldKey", entry.get("fieldKey"));
        normalized.put("visible", !Boolean.FALSE.equals(entry.get("visible")));
        if (entry.get("label") != null) {
            normalized.put("label", String.valueOf(entry.get("label")));
        }
        if (entry.get("width") instanceof Number n) {
            normalized.put("width", n.intValue());
        }
        if (entry.get("colSpan") instanceof Number n) {
            normalized.put("colSpan", n.intValue());
        }
        return normalized;
    }

    private List<Map<String, Object>> defaultLayout(List<MetaField> fields) {
        return fields.stream().map(f -> normalize(Map.of("fieldKey", f.getFieldKey()))).toList();
    }

    private List<MetaField> fieldsOf(Long objectId) {
        MetaObject object = metaObjectMapper.selectById(objectId);
        if (object == null) {
            throw new BizException(ErrorCode.META_OBJECT_NOT_FOUND);
        }
        return metaFieldMapper.selectList(
                new LambdaQueryWrapper<MetaField>().eq(MetaField::getObjectId, objectId)
                        .orderByAsc(MetaField::getSortOrder));
    }

    private void requireType(String layoutType) {
        if (!TYPES.contains(layoutType)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "layoutType 须为 FORM/LIST");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "布局序列化失败");
        }
    }
}

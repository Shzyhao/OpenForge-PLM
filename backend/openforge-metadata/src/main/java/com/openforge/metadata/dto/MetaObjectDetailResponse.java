package com.openforge.metadata.dto;

import com.openforge.metadata.entity.MetaField;
import com.openforge.metadata.entity.MetaObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** 元对象详情（含字段清单）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetaObjectDetailResponse {

    private Long id;
    private String objectKey;
    private String displayName;
    private String tableName;
    private String status;
    private Integer version;
    private LocalDateTime createdAt;
    private List<MetaFieldResponse> fields;

    public static MetaObjectDetailResponse from(MetaObject o, List<MetaField> fields) {
        return new MetaObjectDetailResponse(o.getId(), o.getObjectKey(), o.getDisplayName(),
                o.getTableName(), o.getStatus(), o.getVersion(), o.getCreatedAt(),
                fields.stream().map(MetaFieldResponse::from).toList());
    }
}

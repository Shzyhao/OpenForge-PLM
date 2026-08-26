package com.openforge.metadata.dto;

import com.openforge.metadata.entity.MetaField;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 字段定义响应。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldResponse {

    private Long id;
    private String fieldKey;
    private String displayName;
    private String fieldType;
    private Boolean required;
    private Integer maxLength;
    private String refObject;
    private String refField;
    private Integer sortOrder;

    public static MetaFieldResponse from(MetaField f) {
        return new MetaFieldResponse(f.getId(), f.getFieldKey(), f.getDisplayName(), f.getFieldType(),
                f.getRequired() != null && f.getRequired() == 1, f.getMaxLength(),
                f.getRefObject(), f.getRefField(), f.getSortOrder());
    }
}

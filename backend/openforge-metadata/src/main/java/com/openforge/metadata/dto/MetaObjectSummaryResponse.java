package com.openforge.metadata.dto;

import com.openforge.metadata.entity.MetaObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 元对象列表项（F2 设计 3：含状态/字段数）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MetaObjectSummaryResponse {

    private Long id;
    private String objectKey;
    private String displayName;
    private String tableName;
    private String status;
    private Integer version;
    private Long fieldCount;
    private LocalDateTime createdAt;

    public static MetaObjectSummaryResponse from(MetaObject o, long fieldCount) {
        return new MetaObjectSummaryResponse(o.getId(), o.getObjectKey(), o.getDisplayName(),
                o.getTableName(), o.getStatus(), o.getVersion(), fieldCount, o.getCreatedAt());
    }
}

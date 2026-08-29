package com.openforge.metadata.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** DDL 预览（发布前供确认；与发布时执行的语句同源生成）。 */
@Data
@AllArgsConstructor
public class DdlPreviewResponse {

    private Long objectId;
    private String objectKey;
    private String tableName;
    private String ddl;
}

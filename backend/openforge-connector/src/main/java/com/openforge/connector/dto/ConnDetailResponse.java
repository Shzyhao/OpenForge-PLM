package com.openforge.connector.dto;

import com.openforge.connector.entity.ConnDefinitionVersion;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 连接器详情：主档 + spec + 版本历史。 */
@Data
public class ConnDetailResponse {

    private Long id;
    private String connCode;
    private String connName;
    private String connType;
    private String status;
    private Integer currentVersion;
    private String description;
    private Map<String, Object> spec;
    /** 触发配置（P2-2）：NONE 时 trigger 为空对象 */
    private String triggerType;
    private Map<String, Object> trigger;
    private List<VersionItem> versions;

    @Data
    public static class VersionItem {
        private Integer version;
        private Long publishedBy;
        private LocalDateTime publishedAt;
    }

    public static VersionItem versionOf(ConnDefinitionVersion v) {
        VersionItem item = new VersionItem();
        item.setVersion(v.getVersion());
        item.setPublishedBy(v.getPublishedBy());
        item.setPublishedAt(v.getPublishedAt());
        return item;
    }
}

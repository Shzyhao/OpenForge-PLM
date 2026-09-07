package com.openforge.connector.dto;

import lombok.Data;

/** AI 供应商创建/更新请求（providerCode 不可变；apiKey 留空 = 保留原值）。 */
@Data
public class SaveAiProviderRequest {

    private String providerCode;

    private String providerName;

    /** OpenAI 兼容 base_url */
    private String baseUrl;

    /** 明文 key，仅请求方向（更新时留空 = 保留原值） */
    private String apiKey;

    private String model;

    private Integer timeoutMs;

    private Integer enabled;

    /** 降级链优先级，越小越优先 */
    private Integer priority;
}

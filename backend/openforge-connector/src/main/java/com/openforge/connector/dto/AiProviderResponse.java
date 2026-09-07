package com.openforge.connector.dto;

import com.openforge.connector.entity.AiProvider;
import lombok.Data;

/** AI 供应商视图：永不回显 apiKey。 */
@Data
public class AiProviderResponse {

    private Long id;
    private String providerCode;
    private String providerName;
    private String baseUrl;
    private String model;
    private Integer timeoutMs;
    private Boolean enabled;
    private Integer priority;

    public static AiProviderResponse from(AiProvider provider) {
        AiProviderResponse response = new AiProviderResponse();
        response.setId(provider.getId());
        response.setProviderCode(provider.getProviderCode());
        response.setProviderName(provider.getProviderName());
        response.setBaseUrl(provider.getBaseUrl());
        response.setModel(provider.getModel());
        response.setTimeoutMs(provider.getTimeoutMs());
        response.setEnabled(provider.getEnabled() != null && provider.getEnabled() == 1);
        response.setPriority(provider.getPriority());
        return response;
    }
}

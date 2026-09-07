package com.openforge.connector.dto;

import lombok.Data;

/** AI 供应商连通性测试结果（Java 侧代理执行，apiKey 不出服务）。 */
@Data
public class ProviderTestResponse {

    private String status;
    private Integer httpStatus;
    private Long durationMs;
    private String error;
}

package com.openforge.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体：{ code, message, data, trace_id }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.getCode(), ErrorCode.OK.getMessage(), data, currentTraceId());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, currentTraceId());
    }

    /** 复用请求链路 traceId（网关 X-Trace-Id → MDC）；无过滤器上下文时回退随机值。 */
    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get(com.openforge.common.trace.TraceIdFilter.MDC_KEY);
        return traceId != null ? traceId : java.util.UUID.randomUUID().toString();
    }
}

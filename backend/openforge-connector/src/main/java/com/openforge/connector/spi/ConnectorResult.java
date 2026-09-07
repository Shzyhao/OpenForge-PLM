package com.openforge.connector.spi;

/**
 * 连接器执行结果：失败封装在返回值（HTTP 4xx/5xx、超时等），不抛业务异常——
 * 由调用方（ConnectorRuntime）统一写执行日志与错误码映射。
 */
public record ConnectorResult(
        boolean success,
        Integer httpStatus,
        Integer rowsReturned,
        /** 2xx 响应体原文（>1MB 截断，truncated=true）；失败时为 null */
        String body,
        boolean truncated,
        /** 失败摘要（已脱敏） */
        String error) {

    public static ConnectorResult ok(Integer httpStatus, String body, boolean truncated) {
        return new ConnectorResult(true, httpStatus, null, body, truncated, null);
    }

    public static ConnectorResult fail(Integer httpStatus, String error) {
        return new ConnectorResult(false, httpStatus, null, null, false, error);
    }
}

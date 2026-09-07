package com.openforge.connector.dto;

import com.openforge.connector.spi.ConnectorResult;
import lombok.Data;

/** 试运行/调用结果视图。 */
@Data
public class InvokeResponse {

    private String status;
    private Integer httpStatus;
    private Integer rowsReturned;
    private String body;
    private boolean truncated;
    private String error;
    private long durationMs;

    public static InvokeResponse from(ConnectorResult result, long durationMs) {
        InvokeResponse response = new InvokeResponse();
        response.setStatus(result.success() ? "SUCCESS" : "FAILED");
        response.setHttpStatus(result.httpStatus());
        response.setRowsReturned(result.rowsReturned());
        response.setBody(result.body());
        response.setTruncated(result.truncated());
        response.setError(result.error());
        response.setDurationMs(durationMs);
        return response;
    }
}

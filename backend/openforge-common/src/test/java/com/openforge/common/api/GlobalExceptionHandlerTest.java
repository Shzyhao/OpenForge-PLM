package com.openforge.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/** 全局异常分流单测（v1.20.0 体检补）：404 语义 / DB 掉线限噪 / 常规兜底。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("未映射路径 → 404 + 4001，不再误入 5000 兜底")
    void noResourceMapsTo404() {
        NoResourceFoundException e = new NoResourceFoundException(null, "api/v1/wrong");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResource(e);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("DB 不可达（cause 链含连接失败）→ 明确提示而非系统内部错误")
    void dbUnreachableGivesFriendlyMessage() {
        RuntimeException e = new RuntimeException(" SqlError ",
                new CannotGetJdbcConnectionException("connect failed"));
        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnexpected(e);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().getMessage()).contains("数据库暂不可用");
    }

    @Test
    @DisplayName("非 DB 异常仍走 5000 兜底")
    void unexpectedStillInternal() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnexpected(new IllegalStateException("boom"));
        assertThat(resp.getBody().getMessage()).isEqualTo(ErrorCode.INTERNAL_ERROR.getMessage());
    }
}

package com.openforge.common.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有异常统一收敛为 ApiResponse 结构。
 * HTTP 状态码按错误语义映射（2001→401、2004→403、4xxx→404），其余业务规则错误返回 200 + 业务码。
 * 专项分流（v1.20.0 全模块体检补）：
 * - 未映射路径 NoResourceFoundException → 404（此前落入 5000 兜底：错误 URL 全平台产出
 *   "系统内部错误" + ERROR 级堆栈噪音）；
 * - 数据库暂不可达 → 单行 WARN + 明确提示（DB 长时间掉线时按请求刷 ERROR 堆栈，
 *   实测 13h 累计 5900+ 行日志洪水；服务韧性本身完好——连接恢复后自动痊愈，无需重启）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(httpStatusOf(e.getErrorCode()))
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.INVALID_ARGUMENT.getMessage());
        return ResponseEntity.ok(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, detail));
    }

    /** 路径参数类型不匹配（如 /{id} 传入非数字）→ 1000，非系统错误。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.ok(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT,
                "参数类型不合法: " + e.getName()));
    }

    /** 未映射路径/静态资源缺失 → 404（DEBUG 级，不刷堆栈）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.debug("no resource: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND, "接口不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        if (isDbUnreachable(e)) {
            // DB 掉线期间上游重试仍会按请求进入——单行 WARN 限噪，恢复后自动痊愈
            log.warn("数据库暂不可达（连接获取失败）: {}", rootMessage(e));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "数据库暂不可用，请稍后重试"));
        }
        log.error("unexpected error", e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    /** 异常链中任一环为连接获取失败/数据源资源失效即判 DB 不可达。 */
    private static boolean isDbUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof DataAccessResourceFailureException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private HttpStatus httpStatusOf(ErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED, BAD_CREDENTIALS, ACCOUNT_DISABLED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND, ROLE_NOT_FOUND, PERMISSION_NOT_FOUND,
                 ORG_NOT_FOUND, NUMBER_RULE_NOT_FOUND, META_OBJECT_NOT_FOUND,
                 META_RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.OK;
        };
    }
}

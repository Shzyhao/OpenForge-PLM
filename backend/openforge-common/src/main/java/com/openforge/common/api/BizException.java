package com.openforge.common.api;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转为 ApiResponse。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

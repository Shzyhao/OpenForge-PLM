package com.openforge.connector.spi;

/** 已解密凭据（仅内存态；不落日志/不进异常消息）。 */
public record ResolvedCredential(String authType, String secret, String headerName) {
}

package com.openforge.connector.service;

/** 运行时解密后的凭据值（仅内存态，不落日志/异常/缓存）。 */
public record ResolvedSecret(String authType, String secret, String headerName) {
}

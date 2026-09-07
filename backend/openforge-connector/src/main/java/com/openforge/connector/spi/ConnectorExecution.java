package com.openforge.connector.spi;

import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spec.JdbcReadonlySpec;

import java.util.Map;

/**
 * 连接器执行入参：解析后的 spec + 调用参数 + 已解密凭据（均可为 null——按连接器类型取用）。
 */
public record ConnectorExecution(HttpRestSpec httpSpec, JdbcReadonlySpec jdbcSpec,
                                 Map<String, Object> params, ResolvedCredential credential) {
}

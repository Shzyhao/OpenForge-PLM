package com.openforge.connector.spi;

import com.openforge.connector.spec.DingTalkBotSpec;
import com.openforge.connector.spec.FeishuBotSpec;
import com.openforge.connector.spec.HttpRestSpec;
import com.openforge.connector.spec.JdbcReadonlySpec;
import com.openforge.connector.spec.SmtpEmailSpec;

import java.util.Map;

/**
 * 连接器执行入参：解析后的 spec + 调用参数 + 已解密凭据（均可为 null——按连接器类型取用）。
 */
public record ConnectorExecution(HttpRestSpec httpSpec, JdbcReadonlySpec jdbcSpec,
                                 SmtpEmailSpec smtpSpec, DingTalkBotSpec dingtalkSpec,
                                 FeishuBotSpec feishuSpec,
                                 Map<String, Object> params, ResolvedCredential credential) {

    /** 既有形态构造（HTTP/JDBC 测试沿用）。 */
    public ConnectorExecution(HttpRestSpec httpSpec, JdbcReadonlySpec jdbcSpec,
                              Map<String, Object> params, ResolvedCredential credential) {
        this(httpSpec, jdbcSpec, null, null, null, params, credential);
    }

    /** SMTP 形态构造。 */
    public ConnectorExecution(HttpRestSpec httpSpec, JdbcReadonlySpec jdbcSpec, SmtpEmailSpec smtpSpec,
                              Map<String, Object> params, ResolvedCredential credential) {
        this(httpSpec, jdbcSpec, smtpSpec, null, null, params, credential);
    }
}

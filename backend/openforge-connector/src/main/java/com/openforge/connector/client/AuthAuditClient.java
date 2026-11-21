package com.openforge.connector.client;

import com.openforge.common.web.InternalAuditClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 跨服务操作审计客户端（v1.28 P5#26 收敛到 common InternalAuditClient 基座）。 */
@Component("connectorAuthAuditClient")
public class AuthAuditClient extends InternalAuditClient {

    public AuthAuditClient(@Value("${openforge.security.auth-base-url}") String authBaseUrl,
                           @Value("${openforge.security.internal-token}") String internalToken) {
        super(authBaseUrl, internalToken);
    }

    @Override
    protected String auditSource() {
        return "connector-audit";
    }
}

package com.openforge.doc.client;

import com.openforge.common.web.InternalNotifyClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 站内通知回退通道（v1.28 P5#26 收敛到 common InternalNotifyClient 基座；语义见基类 javadoc）。 */
@Component("docNotifyClient")
public class DocNotifyClient extends InternalNotifyClient {

    public DocNotifyClient(@Value("${openforge.security.auth-base-url:http://localhost:8081}") String authBaseUrl,
                 @Value("${openforge.security.internal-token:openforge-internal-dev-token}") String internalToken) {
        super(authBaseUrl, internalToken);
    }
}

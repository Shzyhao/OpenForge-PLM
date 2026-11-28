package com.openforge.change.client;

import com.openforge.common.web.InternalNumberClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 编号引擎客户端（v1.28 P5#26 收敛到 common InternalNumberClient 基座）。 */
@Component("changeNumberClient")
public class NumberClient extends InternalNumberClient {

    public NumberClient(@Value("${openforge.security.auth-base-url}") String authBaseUrl,
                        @Value("${openforge.security.internal-token}") String internalToken) {
        super(authBaseUrl, internalToken);
    }
}

package com.openforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * security 模块配置。使用方（各业务服务）在 application.yml 中配置：
 * <pre>
 * openforge:
 *   security:
 *     auth-base-url: http://localhost:8081   # auth 服务直连地址（不经网关）
 *     internal-token: xxx                     # 与 auth 的 openforge.internal.token 一致
 * </pre>
 */
@ConfigurationProperties(prefix = "openforge.security")
public class OpenForgeSecurityProperties {

    /** auth 服务直连地址（服务间内网调用，不经网关） */
    private String authBaseUrl = "http://localhost:8081";

    /** 服务间共享令牌 */
    private String internalToken = "openforge-internal-dev-token";

    /** 权限缓存秒数（权限变更最长生效延迟） */
    private long cacheTtlSeconds = 60;

    public String getAuthBaseUrl() {
        return authBaseUrl;
    }

    public void setAuthBaseUrl(String authBaseUrl) {
        this.authBaseUrl = authBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}

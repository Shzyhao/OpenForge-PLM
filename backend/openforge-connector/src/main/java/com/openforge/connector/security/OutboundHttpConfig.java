package com.openforge.connector.security;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 出站 HTTP 客户端（R6 SSRF 根治，v1.19.0）：连接器 HTTP 出站与 AI 供应商连通测试
 * 共用同一实例——连接管理器统一挂 {@link EgressPinningDnsResolver}（解析即校验、所解即所连），
 * 重定向一律禁用（跟随时校验不覆盖，MVP 起的既有语义）。请求级超时由调用方按 spec 设置。
 */
@Configuration
public class OutboundHttpConfig {

    @Bean
    public CloseableHttpClient outboundHttpClient(EgressGuard egressGuard) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new EgressPinningDnsResolver(egressGuard))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10))
                        .build())
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(10)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling() // 重定向可能跳出白名单域，一律不跟（原 JDK 客户端 NEVER 同语义）
                .evictIdleConnections(Timeout.ofSeconds(60))
                .build();
    }
}

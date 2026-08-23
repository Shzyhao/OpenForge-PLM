package com.openforge.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全局认证过滤器：架构文档 3.2 接入层职责的 M1 实现。
 * - 白名单路径直接放行
 * - 其余请求校验 Bearer Token，失败统一 401（ApiResponse 结构）
 * - 验证通过注入信任头（X-User-Id/X-Username/X-Display-Name），
 *   并先清除外部传入的同名头，防止伪造绕过（下游服务只信任网关注入的信任头）
 */
@Slf4j
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_DISPLAY_NAME = "X-Display-Name";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;
    private final List<String> whitelist;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthGlobalFilter(JwtVerifier jwtVerifier,
                            @org.springframework.beans.factory.annotation.Value("${openforge.security.whitelist}") List<String> whitelist) {
        this.jwtVerifier = jwtVerifier;
        this.whitelist = whitelist;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (whitelist.stream().anyMatch(p -> pathMatcher.match(p, path))) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, "缺少 Bearer Token");
        }

        Claims claims;
        try {
            claims = jwtVerifier.verify(auth.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange, "令牌无效或已过期");
        }

        // 清除外部同名头后注入信任头，防止下游被伪造
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_USERNAME);
                    headers.remove(HEADER_DISPLAY_NAME);
                })
                .header(HEADER_USER_ID, String.valueOf(claims.get("uid", Long.class)))
                .header(HEADER_USERNAME, claims.getSubject())
                .header(HEADER_DISPLAY_NAME, claims.get("displayName", String.class))
                .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        log.debug("auth rejected: {} path={}", reason, exchange.getRequest().getPath().value());
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":2001,\"message\":\"" + reason + "\",\"data\":null,\"traceId\":\""
                + java.util.UUID.randomUUID() + "\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Override
    public int getOrder() {
        // 优先于路由过滤器执行
        return -100;
    }
}

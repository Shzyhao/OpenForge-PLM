package com.openforge.gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthGlobalFilterTest {

    private static final String SECRET = "gateway-test-secret-key-32-bytes-abc";
    private static final List<String> WHITELIST = List.of("/api/v1/auth/login", "/actuator/**");

    private SecretKey key;
    private GatewayFilterChain chain;
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        filter = new AuthGlobalFilter(new JwtVerifier(SECRET), WHITELIST);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private String token(long uid, String username) {
        return Jwts.builder()
                .subject(username)
                .claim("uid", uid)
                .claim("displayName", "张三")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    @Test
    @DisplayName("白名单路径直接放行，不做 Token 校验")
    void whitelistShouldPassThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login").build());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("无 Authorization 头：返回 401 且不进入下游")
    void missingTokenShouldReturn401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me").build());

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("无效 Token：返回 401")
    void invalidTokenShouldReturn401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header("Authorization", "Bearer invalid.token.value")
                        .build());

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("有效 Token：清除外部伪造头并注入网关信任头")
    void validTokenShouldInjectTrustedHeadersAndStripSpoofed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token(42L, "zhangsan"))
                        // 外部伪造的信任头，必须被清除
                        .header(AuthGlobalFilter.HEADER_USER_ID, "999")
                        .header(AuthGlobalFilter.HEADER_USERNAME, "attacker")
                        .build());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerWebExchange forwarded = captor.getValue();
        assertThat(forwarded.getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USER_ID)).isEqualTo("42");
        assertThat(forwarded.getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_USERNAME)).isEqualTo("zhangsan");
        assertThat(forwarded.getRequest().getHeaders().getFirst(AuthGlobalFilter.HEADER_DISPLAY_NAME)).isEqualTo("张三");
        // 伪造值不得残留（同名头被整体移除后重设，只保留网关注入的值）
        assertThat(forwarded.getRequest().getHeaders().get(AuthGlobalFilter.HEADER_USER_ID)).hasSize(1);
    }
}

package com.openforge.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-32-bytes-abcdefgh";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 120);
    }

    @Test
    @DisplayName("签发与解析往返：claims 完整还原")
    void generateThenParseRoundTrip() {
        String token = jwtService.generate(42L, "zhangsan", "张三");

        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo("zhangsan");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("displayName", String.class)).isEqualTo("张三");
    }

    @Test
    @DisplayName("过期的令牌解析时应抛 ExpiredJwtException")
    void expiredTokenShouldBeRejected() {
        JwtService expired = new JwtService(SECRET, -1); // ttl 为负 → 生成即过期
        String token = expired.generate(1L, "lisi", null);

        assertThatThrownBy(() -> expired.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("被篡改的令牌解析时应抛 JwtException")
    void tamperedTokenShouldBeRejected() {
        String token = jwtService.generate(1L, "wangwu", null);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("密钥不足 32 字节时拒绝启动")
    void shortSecretShouldFailFast() {
        assertThatThrownBy(() -> new JwtService("too-short", 120))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

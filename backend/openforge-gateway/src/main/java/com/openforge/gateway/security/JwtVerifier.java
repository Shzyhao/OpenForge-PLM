package com.openforge.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 验签。与 auth 服务共享 HS256 密钥（环境变量 JWT_SECRET）。
 * 签名不符或过期抛 io.jsonwebtoken.JwtException，由过滤器统一转 401。
 */
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${openforge.jwt.secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("openforge.jwt.secret 长度必须 >= 32 字节 (HS256 要求)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

package com.openforge.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。HS256；过期/篡改由 jjwt 抛出 JwtException，调用方决定如何响应。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(@Value("${openforge.jwt.secret}") String secret,
                      @Value("${openforge.jwt.ttl-minutes:120}") long ttlMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("openforge.jwt.secret 长度必须 >= 32 字节 (HS256 要求)");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    public String generate(Long userId, String username, String displayName) {
        return generate(userId, username, displayName, com.openforge.common.tenant.TenantContext.DEFAULT_TENANT);
    }

    /** 携带租户声明（架构文档 7.3：JWT 携带租户，网关转发 X-User-Tenant）。 */
    public String generate(Long userId, String username, String displayName, Long tenantId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("displayName", displayName == null ? "" : displayName)
                .claim("tenant", tenantId == null ? com.openforge.common.tenant.TenantContext.DEFAULT_TENANT : tenantId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMinutes * 60_000))
                .signWith(key)
                .compact();
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    /** 校验并解析；签名不符或过期将抛 io.jsonwebtoken.JwtException */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}

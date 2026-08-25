package com.openforge.auth.dto;

import lombok.Data;

/** 登录响应（方案 E2：密码状态三态 + 临期天数）。 */
@Data
public class TokenResponse {
    private String accessToken;
    private String tokenType;
    private long expiresInSeconds;

    /** OK / EXPIRING_SOON / EXPIRED / FORCE_CHANGE */
    private String passwordStatus;

    /** 距过期天数（EXPIRING_SOON 时 >0） */
    private Long daysToExpiry;

    public static TokenResponse of(String token, long ttlMinutes, String passwordStatus, Long daysToExpiry) {
        TokenResponse t = new TokenResponse();
        t.accessToken = token;
        t.tokenType = "Bearer";
        t.expiresInSeconds = ttlMinutes * 60;
        t.passwordStatus = passwordStatus;
        t.daysToExpiry = daysToExpiry;
        return t;
    }
}

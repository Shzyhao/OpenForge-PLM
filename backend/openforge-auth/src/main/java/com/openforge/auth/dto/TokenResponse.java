package com.openforge.auth.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static TokenResponse of(String token, long ttlMinutes) {
        return new TokenResponse(token, "Bearer", ttlMinutes * 60);
    }
}

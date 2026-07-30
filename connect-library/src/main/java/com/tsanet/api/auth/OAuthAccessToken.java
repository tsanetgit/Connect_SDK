package com.tsanet.api.auth;

public record OAuthAccessToken(String accessToken, long expiresInSeconds) {
    public OAuthAccessToken {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("OAuth token response did not include access_token");
        }
        if (expiresInSeconds <= 0) {
            expiresInSeconds = 3600;
        }
    }
}

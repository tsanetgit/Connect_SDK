package com.tsanet.api.auth;

public record PasswordAuthConfig(String username, String password) implements AccountAuthConfig {
    @Override
    public String toString() {
        return "PasswordAuthConfig[username=" + username + ", password=<redacted>]";
    }

    public PasswordAuthConfig {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required for connect1-password auth");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required for connect1-password auth");
        }
    }

    @Override
    public AuthMode mode() {
        return AuthMode.CONNECT1_PASSWORD;
    }
}

package com.tsanet.clientdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String username, String password) {

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}

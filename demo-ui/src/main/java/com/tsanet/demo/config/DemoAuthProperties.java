package com.tsanet.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional HTTP Basic gate for hosted deployments. Auth is enabled only when
 * a password is configured (TSANET_DEMO_AUTH_PASSWORD); with no password the
 * app stays open, which keeps local development friction-free.
 */
@ConfigurationProperties(prefix = "tsanet.demo.auth")
public record DemoAuthProperties(String username, String password) {

    public boolean enabled() {
        return password != null && !password.isBlank();
    }
}

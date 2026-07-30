package com.tsanet.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.webhook")
public record WebhookProperties(
    boolean enabled,
    int port,
    String path,
    String publicBaseUrl
) {
    public String callbackUrl() {
        String base = publicBaseUrl != null ? publicBaseUrl.strip() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String endpoint = path != null && path.startsWith("/") ? path : "/" + (path != null ? path : "webhooks/tsanet");
        return base + endpoint;
    }
}

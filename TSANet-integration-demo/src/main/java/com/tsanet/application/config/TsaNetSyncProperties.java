package com.tsanet.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.sync")
public record TsaNetSyncProperties(
    boolean enabled,
    long initialDelayMs,
    long intervalMs,
    boolean pollScenarioAccounts
) {
    public TsaNetSyncProperties {
        if (initialDelayMs <= 0) {
            initialDelayMs = 10_000L;
        }
        if (intervalMs <= 0) {
            intervalMs = 60_000L;
        }
    }
}

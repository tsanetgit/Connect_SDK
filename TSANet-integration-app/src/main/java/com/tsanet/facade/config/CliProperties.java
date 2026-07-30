package com.tsanet.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.cli")
public record CliProperties(boolean enabled) {
}

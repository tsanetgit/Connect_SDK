package com.tsanet.clientdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.api")
public record ApiProperties(String baseUrl, String loginPath) {
}

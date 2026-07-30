package com.tsanet.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet.scenarios")
public record TsaNetScenarioProperties(
    boolean runOnStartup,
    CompanyCredentials acme,
    CompanyCredentials beta
) {
    public record CompanyCredentials(String username, String password) {
    }
}

package com.tsanet.application.setup;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tsanet")
public record ConnectApiDatabaseProperties(
    Demo demo,
    ConnectDb connectDb
) {
    public record Demo(Setup setup) {
    }

    public record Setup(boolean enabled) {
    }

    public record ConnectDb(String url, String username, String password) {
    }
}

package com.tsanet.api;

public record TsaNetApiConfiguration(
    String apiBaseUrl,
    String sqlitePath,
    String username,
    String password
) {
    public TsaNetApiConfiguration {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }
        if (sqlitePath == null || sqlitePath.isBlank()) {
            throw new IllegalArgumentException("sqlitePath is required");
        }
    }

    public static TsaNetApiConfiguration of(String apiBaseUrl, String sqlitePath, String username, String password) {
        return new TsaNetApiConfiguration(apiBaseUrl, sqlitePath, username, password);
    }
}

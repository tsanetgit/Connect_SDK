package com.tsanet.api;

/**
 * Connect API endpoint and local SQLite cache location shared by multiple sessions.
 *
 * <p>Credentials are supplied per session when calling {@link TsaNetApiSessionFactory#openSession}.
 */
public record TsaNetApiConnectionSettings(String apiBaseUrl, String sqliteBasePath) {
    public TsaNetApiConnectionSettings {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }
        if (sqliteBasePath == null || sqliteBasePath.isBlank()) {
            throw new IllegalArgumentException("sqliteBasePath is required");
        }
    }

    public static TsaNetApiConnectionSettings of(String apiBaseUrl, String sqliteBasePath) {
        return new TsaNetApiConnectionSettings(apiBaseUrl, sqliteBasePath);
    }
}

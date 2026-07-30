package com.tsanet.api;

import com.tsanet.api.auth.ClientCredentialsAuthConfig;

public final class ApplicationUserAccountConfigMapper {
    private ApplicationUserAccountConfigMapper() {
    }

    public static ApplicationUserAccount passwordAccount(String id, String sqlitePath, String username, String password) {
        return ApplicationUserAccount.passwordAccount(id, sqlitePath, username, password);
    }

    public static ApplicationUserAccount clientCredentialsAccount(
        String id,
        String sqlitePath,
        String tenantId,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String audience,
        String scope
    ) {
        return new ApplicationUserAccount(
            id,
            sqlitePath,
            new ClientCredentialsAuthConfig(tenantId, tokenUrl, clientId, clientSecret, audience, scope)
        );
    }

    public static ApplicationUserAccount fromAuthType(
        String id,
        String sqlitePath,
        String authType,
        String username,
        String password,
        String tenantId,
        String tokenUrl,
        String clientId,
        String clientSecret,
        String audience,
        String scope
    ) {
        if (authType != null && authType.equalsIgnoreCase("client-credentials")) {
            return clientCredentialsAccount(id, sqlitePath, tenantId, tokenUrl, clientId, clientSecret, audience, scope);
        }
        return passwordAccount(id, sqlitePath, username, password);
    }

    public static ApplicationUserAccount fromLegacyFields(String id, String sqlitePath, String username, String password) {
        return passwordAccount(id, sqlitePath, username, password);
    }
}

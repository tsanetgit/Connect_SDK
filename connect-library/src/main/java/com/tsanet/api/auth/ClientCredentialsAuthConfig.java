package com.tsanet.api.auth;

public record ClientCredentialsAuthConfig(
    String tenantId,
    String tokenUrl,
    String clientId,
    String clientSecret,
    String audience,
    String scope
) implements AccountAuthConfig {
    public ClientCredentialsAuthConfig {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required for client-credentials auth");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientSecret is required for client-credentials auth");
        }
        if ((tenantId == null || tenantId.isBlank()) && (tokenUrl == null || tokenUrl.isBlank())) {
            throw new IllegalArgumentException("tenantId or tokenUrl is required for client-credentials auth");
        }
        if ((audience == null || audience.isBlank()) && (scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("audience or scope is required for client-credentials auth");
        }
    }

    @Override
    public AuthMode mode() {
        return AuthMode.CLIENT_CREDENTIALS;
    }

    public String resolvedTokenUrl() {
        if (tokenUrl != null && !tokenUrl.isBlank()) {
            return tokenUrl.trim();
        }
        return "https://login.microsoftonline.com/" + tenantId.trim() + "/oauth2/v2.0/token";
    }

    public String resolvedScope() {
        if (scope != null && !scope.isBlank()) {
            return scope.trim();
        }
        String normalizedAudience = audience.trim();
        if (normalizedAudience.endsWith("/.default")) {
            return normalizedAudience;
        }
        return normalizedAudience + "/.default";
    }
}

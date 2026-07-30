package com.tsanet.api.connectapi.internal;

import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public final class TokenManager {
    static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final ConnectApiSessionStore sessionStore;
    private final ConnectApiAuthGateway passwordAuthGateway;
    private final OAuthTokenGateway oauthTokenGateway;
    private final AccountAuthConfig authConfig;
    private final String accountId;
    private final Clock clock;

    public TokenManager(
        ConnectApiSessionStore sessionStore,
        ConnectApiAuthGateway passwordAuthGateway,
        OAuthTokenGateway oauthTokenGateway,
        String accountId,
        AccountAuthConfig authConfig
    ) {
        this(sessionStore, passwordAuthGateway, oauthTokenGateway, accountId, authConfig, Clock.systemUTC());
    }

    TokenManager(
        ConnectApiSessionStore sessionStore,
        ConnectApiAuthGateway passwordAuthGateway,
        OAuthTokenGateway oauthTokenGateway,
        String accountId,
        AccountAuthConfig authConfig,
        Clock clock
    ) {
        this.sessionStore = sessionStore;
        this.passwordAuthGateway = passwordAuthGateway;
        this.oauthTokenGateway = oauthTokenGateway;
        this.accountId = accountId;
        this.authConfig = authConfig;
        this.clock = clock;
    }

    public String authenticate() {
        return switch (authConfig.mode()) {
            case CLIENT_CREDENTIALS -> authenticateClientCredentials((ClientCredentialsAuthConfig) authConfig);
            case CONNECT1_PASSWORD -> authenticatePassword((PasswordAuthConfig) authConfig);
        };
    }

    public String ensureValidAccessToken() {
        if (authConfig.mode() == AuthMode.CLIENT_CREDENTIALS && sessionStore.isExpired(clock.instant())) {
            return refreshAccessToken();
        }
        return sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not authenticated"));
    }

    public String refreshAccessToken() {
        if (authConfig.mode() != AuthMode.CLIENT_CREDENTIALS) {
            throw new IllegalStateException("Token refresh is only supported for client-credentials auth");
        }
        return authenticateClientCredentials((ClientCredentialsAuthConfig) authConfig);
    }

    public boolean supportsRefresh() {
        return authConfig.mode() == AuthMode.CLIENT_CREDENTIALS;
    }

    public AuthMode authMode() {
        return authConfig.mode();
    }

    public Optional<Instant> tokenExpiresAt() {
        return sessionStore.getExpiresAt();
    }

    private String authenticateClientCredentials(ClientCredentialsAuthConfig config) {
        OAuthAccessToken token = oauthTokenGateway.fetchClientCredentialsToken(config);
        Instant expiresAt = clock.instant().plusSeconds(token.expiresInSeconds());
        sessionStore.saveOAuth(accountId, token.accessToken(), expiresAt);
        return token.accessToken();
    }

    private String authenticatePassword(PasswordAuthConfig config) {
        String token = passwordAuthGateway.login(config.username(), config.password());
        sessionStore.savePassword(config.username(), token);
        return token;
    }
}

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

    /** The bearer for the next request, renewed first when the session knows it has expired. */
    public String ensureValidAccessToken() {
        if (sessionStore.isExpired(clock.instant())) {
            return refreshAccessToken();
        }
        return sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not authenticated"));
    }

    /**
     * Renews the bearer: a fresh client-credentials token, or a transparent re-login with the
     * configured password. The platform has no refresh endpoint, so re-login is the strategy,
     * and it is offered only when the session belongs to the configured user; a session opened
     * by an interactively typed password is never renewed silently, because that password was
     * never retained.
     */
    public String refreshAccessToken() {
        return switch (authConfig.mode()) {
            case CLIENT_CREDENTIALS -> authenticateClientCredentials((ClientCredentialsAuthConfig) authConfig);
            case CONNECT1_PASSWORD -> {
                if (!canReloginWithConfiguredPassword()) {
                    throw new IllegalStateException(
                        "Session expired and cannot be renewed with the configured credentials; log in again");
                }
                yield authenticatePassword((PasswordAuthConfig) authConfig);
            }
        };
    }

    public boolean supportsRefresh() {
        return authConfig.mode() == AuthMode.CLIENT_CREDENTIALS || canReloginWithConfiguredPassword();
    }

    /** An interactive login with the given credentials, with the same expiry tracking as a configured one. */
    public String loginWithPassword(String username, String password) {
        return authenticatePassword(new PasswordAuthConfig(username, password));
    }

    private boolean canReloginWithConfiguredPassword() {
        if (!(authConfig instanceof PasswordAuthConfig configured)
            || configured.username() == null || configured.username().isBlank()
            || configured.password() == null || configured.password().isBlank()) {
            return false;
        }
        return sessionStore.getUsername().map(configured.username()::equals).orElse(false);
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
        PasswordLogin login = passwordAuthGateway.login(config.username(), config.password());
        Instant expiresAt = login.expiresInSeconds() == null ? null : clock.instant().plusSeconds(login.expiresInSeconds());
        sessionStore.savePassword(config.username(), login.accessToken(), expiresAt);
        return login.accessToken();
    }
}

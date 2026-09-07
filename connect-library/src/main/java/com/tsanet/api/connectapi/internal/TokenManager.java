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
import java.util.concurrent.locks.ReentrantLock;

public final class TokenManager {
    static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final ConnectApiSessionStore sessionStore;
    private final ConnectApiAuthGateway passwordAuthGateway;
    private final OAuthTokenGateway oauthTokenGateway;
    private final AccountAuthConfig authConfig;
    private final String accountId;
    private final Clock clock;
    /** Renewals run one at a time; a caller who waited re-reads the store before renewing again. */
    private final ReentrantLock renewal = new ReentrantLock();

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

    /**
     * The bearer for the next request, renewed first when the session knows it has expired.
     * Concurrent callers that all find the token expired share one renewal: the first renews,
     * the rest wait and then read the token it stored.
     */
    public String ensureValidAccessToken() {
        ConnectApiSessionStore.Snapshot seen = sessionStore.snapshot();
        if (!seen.isExpired(clock.instant())) {
            return bearerOf(seen);
        }
        renewal.lock();
        try {
            ConnectApiSessionStore.Snapshot current = sessionStore.snapshot();
            if (!current.isExpired(clock.instant())) {
                return bearerOf(current);
            }
            return renew();
        } finally {
            renewal.unlock();
        }
    }

    /**
     * Renews the bearer: a fresh client-credentials token, or a transparent re-login with the
     * configured password. The platform has no refresh endpoint, so re-login is the strategy,
     * and it is offered only when the session belongs to the configured user; a session opened
     * by an interactively typed password is never renewed silently, because that password was
     * never retained. Always renews, even when another caller just did; see
     * {@link #renewUnlessAlreadyRenewed(String)} for the 401 path.
     */
    public String refreshAccessToken() {
        renewal.lock();
        try {
            return renew();
        } finally {
            renewal.unlock();
        }
    }

    /**
     * The 401 path's renewal. {@code observedToken} is the bearer the rejected request carried.
     * If, by the time this caller holds the renewal lock, the store already holds a different
     * unexpired bearer, another caller's renewal has answered this 401 too and that bearer is
     * returned with no network call. If the store still holds the observed bearer it is bad
     * server-side whatever its expiry says, and one renewal runs.
     */
    public String renewUnlessAlreadyRenewed(String observedToken) {
        renewal.lock();
        try {
            ConnectApiSessionStore.Snapshot current = sessionStore.snapshot();
            if (current.hasBearer()
                && !current.bearerToken().equals(observedToken)
                && !current.isExpired(clock.instant())) {
                return current.bearerToken();
            }
            return renew();
        } finally {
            renewal.unlock();
        }
    }

    private static String bearerOf(ConnectApiSessionStore.Snapshot snapshot) {
        if (!snapshot.hasBearer()) {
            throw new IllegalStateException("Not authenticated");
        }
        return snapshot.bearerToken();
    }

    private String renew() {
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
        sessionStore.saveOAuth(accountId, token.accessToken(), expiryFrom(token.expiresInSeconds()));
        return token.accessToken();
    }

    private String authenticatePassword(PasswordAuthConfig config) {
        PasswordLogin login = passwordAuthGateway.login(config.username(), config.password());
        Long lifetime = login.expiresInSeconds() == null ? null : login.expiresInSeconds().longValue();
        sessionStore.savePassword(config.username(), login.accessToken(), expiryFrom(lifetime));
        return login.accessToken();
    }

    /**
     * When the stated lifetime is at or below {@link #EXPIRY_SKEW} the token would count as
     * expired the moment it arrived, and every request would open with a login (or, for an
     * interactively typed password, fail as "log in again" right after a successful login).
     * Such a lifetime is treated as unstated: no expiry is tracked and the 401 path handles it.
     */
    private Instant expiryFrom(Long lifetimeSeconds) {
        if (lifetimeSeconds == null || lifetimeSeconds <= EXPIRY_SKEW.getSeconds()) {
            return null;
        }
        return clock.instant().plusSeconds(lifetimeSeconds);
    }
}

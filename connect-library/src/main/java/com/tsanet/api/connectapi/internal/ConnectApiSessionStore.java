package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.auth.AuthMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public class ConnectApiSessionStore {
    private volatile String bearerToken;
    private volatile String username;
    private volatile String accountId;
    private volatile AuthMode authMode;
    private volatile Instant expiresAt;
    private volatile UserContextDto userContext;

    public void savePassword(String username, String bearerToken) {
        savePassword(username, bearerToken, null);
    }

    /** {@code expiresAt} null means the API stated no lifetime; expiry then surfaces only as a 401. */
    public void savePassword(String username, String bearerToken, Instant expiresAt) {
        forgetUserContextUnlessSamePrincipal(AuthMode.CONNECT1_PASSWORD, username);
        this.username = username;
        this.bearerToken = bearerToken;
        this.authMode = AuthMode.CONNECT1_PASSWORD;
        this.expiresAt = expiresAt;
    }

    /** The company and user behind the session, fetched from {@code /v1/me} right after login. */
    public void saveUserContext(UserContextDto userContext) {
        this.userContext = userContext;
    }

    public Optional<UserContextDto> getUserContext() {
        return Optional.ofNullable(userContext);
    }

    public void saveOAuth(String accountId, String bearerToken, Instant expiresAt) {
        forgetUserContextUnlessSamePrincipal(AuthMode.CLIENT_CREDENTIALS, accountId);
        this.accountId = accountId;
        this.username = accountId;
        this.bearerToken = bearerToken;
        this.authMode = AuthMode.CLIENT_CREDENTIALS;
        this.expiresAt = expiresAt;
    }

    /**
     * A renewed bearer for the same principal (the configured user re-logged in, or a fresh
     * client-credentials token) leaves the {@code /v1/me} context in place: it describes the
     * principal, not the token. Only a different principal, or a change of mode, invalidates it.
     */
    private void forgetUserContextUnlessSamePrincipal(AuthMode mode, String principal) {
        if (this.authMode != mode || !Objects.equals(this.username, principal)) {
            this.userContext = null;
        }
    }

    public Optional<String> getBearerToken() {
        return Optional.ofNullable(bearerToken);
    }

    public Optional<String> getUsername() {
        return Optional.ofNullable(username);
    }

    public Optional<String> getAccountId() {
        return Optional.ofNullable(accountId);
    }

    public Optional<AuthMode> getAuthMode() {
        return Optional.ofNullable(authMode);
    }

    public Optional<Instant> getExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    public boolean isAuthorized() {
        return bearerToken != null && !bearerToken.isBlank() && !isExpired(Instant.now());
    }

    public boolean isExpired(Instant now) {
        if (expiresAt == null) {
            return false;
        }
        return !now.isBefore(expiresAt.minus(TokenManager.EXPIRY_SKEW));
    }

    public void clear() {
        this.username = null;
        this.bearerToken = null;
        this.accountId = null;
        this.authMode = null;
        this.expiresAt = null;
        this.userContext = null;
    }
}

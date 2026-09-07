package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.auth.AuthMode;
import java.time.Instant;
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
        this.username = username;
        this.bearerToken = bearerToken;
        this.authMode = AuthMode.CONNECT1_PASSWORD;
        this.expiresAt = expiresAt;
        this.userContext = null;
    }

    /** The company and user behind the session, fetched from {@code /v1/me} right after login. */
    public void saveUserContext(UserContextDto userContext) {
        this.userContext = userContext;
    }

    public Optional<UserContextDto> getUserContext() {
        return Optional.ofNullable(userContext);
    }

    public void saveOAuth(String accountId, String bearerToken, Instant expiresAt) {
        this.accountId = accountId;
        this.username = accountId;
        this.bearerToken = bearerToken;
        this.authMode = AuthMode.CLIENT_CREDENTIALS;
        this.expiresAt = expiresAt;
        this.userContext = null;
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

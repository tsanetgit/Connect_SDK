package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.auth.AuthMode;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The session's authentication state, held as one immutable {@link Snapshot} behind a single
 * reference. Every write replaces the whole snapshot and every read takes one, so a reader
 * never sees a bearer from one login beside the username or expiry of another, and
 * {@link #isExpired} and {@link #isAuthorized} judge a token against its own expiry.
 */
public class ConnectApiSessionStore {

    /** One coherent view of the session. Package-private so the token manager can read it whole. */
    record Snapshot(
        String bearerToken,
        String username,
        String accountId,
        AuthMode authMode,
        Instant expiresAt,
        UserContextDto userContext
    ) {
        static final Snapshot EMPTY = new Snapshot(null, null, null, null, null, null);

        boolean isExpired(Instant now) {
            if (expiresAt == null) {
                return false;
            }
            return !now.isBefore(expiresAt.minus(TokenManager.EXPIRY_SKEW));
        }

        boolean hasBearer() {
            return bearerToken != null && !bearerToken.isBlank();
        }
    }

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    public void savePassword(String username, String bearerToken) {
        savePassword(username, bearerToken, null);
    }

    /** {@code expiresAt} null means the API stated no lifetime; expiry then surfaces only as a 401. */
    public synchronized void savePassword(String username, String bearerToken, Instant expiresAt) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
            bearerToken,
            username,
            current.accountId(),
            AuthMode.CONNECT1_PASSWORD,
            expiresAt,
            userContextUnlessPrincipalChanged(current, AuthMode.CONNECT1_PASSWORD, username)
        );
    }

    public synchronized void saveOAuth(String accountId, String bearerToken, Instant expiresAt) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(
            bearerToken,
            accountId,
            accountId,
            AuthMode.CLIENT_CREDENTIALS,
            expiresAt,
            userContextUnlessPrincipalChanged(current, AuthMode.CLIENT_CREDENTIALS, accountId)
        );
    }

    /** The company and user behind the session, fetched from {@code /v1/me} right after login. */
    public synchronized void saveUserContext(UserContextDto userContext) {
        Snapshot current = snapshot;
        snapshot = new Snapshot(current.bearerToken(), current.username(), current.accountId(),
            current.authMode(), current.expiresAt(), userContext);
    }

    /**
     * A renewed bearer for the same principal (the configured user re-logged in, or a fresh
     * client-credentials token) leaves the {@code /v1/me} context in place: it describes the
     * principal, not the token. Only a different principal, or a change of mode, invalidates it.
     */
    private static UserContextDto userContextUnlessPrincipalChanged(Snapshot current, AuthMode mode, String principal) {
        if (current.authMode() != mode || !Objects.equals(current.username(), principal)) {
            return null;
        }
        return current.userContext();
    }

    Snapshot snapshot() {
        return snapshot;
    }

    public Optional<UserContextDto> getUserContext() {
        return Optional.ofNullable(snapshot.userContext());
    }

    public Optional<String> getBearerToken() {
        return Optional.ofNullable(snapshot.bearerToken());
    }

    public Optional<String> getUsername() {
        return Optional.ofNullable(snapshot.username());
    }

    public Optional<String> getAccountId() {
        return Optional.ofNullable(snapshot.accountId());
    }

    public Optional<AuthMode> getAuthMode() {
        return Optional.ofNullable(snapshot.authMode());
    }

    public Optional<Instant> getExpiresAt() {
        return Optional.ofNullable(snapshot.expiresAt());
    }

    public boolean isAuthorized() {
        Snapshot current = snapshot;
        return current.hasBearer() && !current.isExpired(Instant.now());
    }

    public boolean isExpired(Instant now) {
        return snapshot.isExpired(now);
    }

    /**
     * Logout. Synchronized like the saves, so a save's read-modify-write cannot straddle it and
     * write back the pre-logout session. What it does not prevent: a renewal already past its
     * network call stores its token afterwards, and a straggling 401 on a client-credentials
     * session finds an empty store and logs in again (the 401 path renews on an empty store,
     * see {@link TokenManager#renewUnlessAlreadyRenewed}). Both predate this class's snapshot
     * and are left as they were.
     */
    public synchronized void clear() {
        snapshot = Snapshot.EMPTY;
    }
}

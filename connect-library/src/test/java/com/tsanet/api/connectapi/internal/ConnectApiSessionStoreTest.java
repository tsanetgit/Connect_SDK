package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.auth.AuthMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConnectApiSessionStoreTest {
    @Test
    void itTracksPasswordSessionWithoutExpiry() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.savePassword("api@test.com", "token");

        assertThat(store.isAuthorized()).isTrue();
        assertThat(store.getUsername()).contains("api@test.com");
        assertThat(store.getAuthMode()).contains(AuthMode.CONNECT1_PASSWORD);
        assertThat(store.getExpiresAt()).isEmpty();
        assertThat(store.isExpired(Instant.now())).isFalse();
    }

    @Test
    void itMarksOAuthTokenExpiredWithinSkewWindow() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        Instant expiresAt = Instant.parse("2026-01-01T12:00:00Z");
        store.saveOAuth("production", "oauth-token", expiresAt);

        assertThat(store.getAuthMode()).contains(AuthMode.CLIENT_CREDENTIALS);
        assertThat(store.getAccountId()).contains("production");
        assertThat(store.isExpired(expiresAt.minusSeconds(TokenManager.EXPIRY_SKEW.getSeconds() - 1))).isTrue();
        assertThat(store.isExpired(expiresAt.minusSeconds(TokenManager.EXPIRY_SKEW.getSeconds() + 30))).isFalse();
    }

    @Test
    void itClearsAuthorizationOnLogout() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.saveOAuth("production", "oauth-token", Instant.parse("2026-01-01T12:00:00Z"));

        store.clear();

        assertThat(store.isAuthorized()).isFalse();
        assertThat(store.getBearerToken()).isEmpty();
    }
}

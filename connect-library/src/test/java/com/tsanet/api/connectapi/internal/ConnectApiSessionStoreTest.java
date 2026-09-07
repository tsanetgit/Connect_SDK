package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.connectapi.dto.UserContextDto;
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
    void aRenewedBearerForTheSamePrincipalKeepsTheUserContext() {
        UserContextDto context = new UserContextDto(7L, "Acme", 42L, "api@test.com", "api@test.com", "A", "Pi");

        ConnectApiSessionStore password = new ConnectApiSessionStore();
        password.savePassword("api@test.com", "t1", Instant.parse("2026-01-01T12:00:00Z"));
        password.saveUserContext(context);
        password.savePassword("api@test.com", "t2", Instant.parse("2026-01-01T13:00:00Z"));
        assertThat(password.getUserContext()).contains(context);
        assertThat(password.getBearerToken()).contains("t2");

        ConnectApiSessionStore oauth = new ConnectApiSessionStore();
        oauth.saveOAuth("production", "o1", Instant.parse("2026-01-01T12:00:00Z"));
        oauth.saveUserContext(context);
        oauth.saveOAuth("production", "o2", Instant.parse("2026-01-01T13:00:00Z"));
        assertThat(oauth.getUserContext()).contains(context);
    }

    @Test
    void aBearerForADifferentPrincipalDropsTheUserContext() {
        UserContextDto context = new UserContextDto(7L, "Acme", 42L, "api@test.com", "api@test.com", "A", "Pi");

        ConnectApiSessionStore otherUser = new ConnectApiSessionStore();
        otherUser.savePassword("api@test.com", "t1");
        otherUser.saveUserContext(context);
        otherUser.savePassword("other@test.com", "t2");
        assertThat(otherUser.getUserContext()).isEmpty();

        ConnectApiSessionStore otherMode = new ConnectApiSessionStore();
        otherMode.savePassword("production", "t1");
        otherMode.saveUserContext(context);
        otherMode.saveOAuth("production", "o1", Instant.parse("2026-01-01T12:00:00Z"));
        assertThat(otherMode.getUserContext()).isEmpty();
    }

    @Test
    void aReaderSeesOneLoginWholeNeverATokenBesideAnotherLoginsExpiry() throws Exception {
        // Two logins alternate as fast as a writer can go; every snapshot a reader takes must pair
        // a token with its own expiry and username. Field-by-field writes let this tear.
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        Instant expiresA = Instant.parse("2026-01-01T12:00:00Z");
        Instant expiresB = Instant.parse("2026-06-01T12:00:00Z");
        store.saveOAuth("a", "token-a", expiresA);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            boolean a = false;
            while (!stop.get()) {
                if (a) {
                    store.saveOAuth("a", "token-a", expiresA);
                } else {
                    store.savePassword("b", "token-b", expiresB);
                }
                a = !a;
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 200_000; i++) {
                ConnectApiSessionStore.Snapshot seen = store.snapshot();
                if ("token-a".equals(seen.bearerToken())) {
                    assertThat(seen.expiresAt()).isEqualTo(expiresA);
                    assertThat(seen.username()).isEqualTo("a");
                    assertThat(seen.authMode()).isEqualTo(AuthMode.CLIENT_CREDENTIALS);
                } else {
                    assertThat(seen.bearerToken()).isEqualTo("token-b");
                    assertThat(seen.expiresAt()).isEqualTo(expiresB);
                    assertThat(seen.username()).isEqualTo("b");
                    assertThat(seen.authMode()).isEqualTo(AuthMode.CONNECT1_PASSWORD);
                }
            }
        } finally {
            stop.set(true);
            writer.join(5_000);
        }
    }

    @Test
    void aPasswordLoginKeepsTheAccountIdOfTheSessionItRenews() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.saveOAuth("production", "o1", Instant.parse("2026-01-01T12:00:00Z"));

        store.savePassword("user@test.com", "t1");

        assertThat(store.getAccountId()).contains("production");
        assertThat(store.getUsername()).contains("user@test.com");
        assertThat(store.getAuthMode()).contains(AuthMode.CONNECT1_PASSWORD);
        assertThat(store.getExpiresAt()).isEmpty();
    }

    @Test
    void itClearsAuthorizationOnLogout() {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.saveOAuth("production", "oauth-token", Instant.parse("2026-01-01T12:00:00Z"));

        store.clear();

        assertThat(store.isAuthorized()).isFalse();
        assertThat(store.getBearerToken()).isEmpty();
        assertThat(store.getUsername()).isEmpty();
        assertThat(store.getAccountId()).isEmpty();
        assertThat(store.getAuthMode()).isEmpty();
        assertThat(store.getExpiresAt()).isEmpty();
        assertThat(store.getUserContext()).isEmpty();
    }
}

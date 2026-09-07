package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenManagerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void itAuthenticatesWithConfiguredPassword() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("password-token", 3600));

        TokenManager tokenManager = new TokenManager(
            sessionStore,
            authGateway,
            mock(OAuthTokenGateway.class),
            "default",
            new PasswordAuthConfig("user@test.com", "secret"),
            CLOCK
        );

        assertThat(tokenManager.authenticate()).isEqualTo("password-token");
        assertThat(sessionStore.getAuthMode()).contains(AuthMode.CONNECT1_PASSWORD);
        assertThat(tokenManager.authMode()).isEqualTo(AuthMode.CONNECT1_PASSWORD);
    }

    @Test
    void itRefreshesExpiredOAuthTokenBeforeUse() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.saveOAuth("production", "expired-token", NOW.minusSeconds(30));

        OAuthTokenGateway oauthTokenGateway = mock(OAuthTokenGateway.class);
        ClientCredentialsAuthConfig config = new ClientCredentialsAuthConfig(
            "tenant",
            null,
            "client-id",
            "client-secret",
            "api://audience",
            null
        );
        when(oauthTokenGateway.fetchClientCredentialsToken(config))
            .thenReturn(new OAuthAccessToken("fresh-token", 3600));

        TokenManager tokenManager = new TokenManager(
            sessionStore,
            mock(ConnectApiAuthGateway.class),
            oauthTokenGateway,
            "production",
            config,
            CLOCK
        );

        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("fresh-token");
        assertThat(sessionStore.getBearerToken()).contains("fresh-token");
        verify(oauthTokenGateway).fetchClientCredentialsToken(config);
    }

    @Test
    void itKeepsValidOAuthTokenWithoutRefreshing() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.saveOAuth("production", "valid-token", NOW.plusSeconds(600));

        OAuthTokenGateway oauthTokenGateway = mock(OAuthTokenGateway.class);
        ClientCredentialsAuthConfig config = new ClientCredentialsAuthConfig(
            "tenant",
            null,
            "client-id",
            "client-secret",
            "api://audience",
            null
        );

        TokenManager tokenManager = new TokenManager(
            sessionStore,
            mock(ConnectApiAuthGateway.class),
            oauthTokenGateway,
            "production",
            config,
            CLOCK
        );

        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("valid-token");
        verify(oauthTokenGateway, never()).fetchClientCredentialsToken(any());
    }

    @Test
    void itRejectsPasswordRefreshWhenNoSessionBelongsToTheConfiguredUser() {
        TokenManager tokenManager = new TokenManager(
            new ConnectApiSessionStore(),
            mock(ConnectApiAuthGateway.class),
            mock(OAuthTokenGateway.class),
            "default",
            new PasswordAuthConfig("user@test.com", "secret"),
            CLOCK
        );

        assertThat(tokenManager.supportsRefresh()).isFalse();
        assertThatThrownBy(tokenManager::refreshAccessToken)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("log in again");
    }

    @Test
    void itTracksPasswordExpiryFromTheLoginResponse() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("t1", 1800));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        tokenManager.authenticate();

        assertThat(sessionStore.getExpiresAt()).contains(NOW.plusSeconds(1800));
        assertThat(sessionStore.isExpired(NOW.plusSeconds(1800 - 61))).isFalse();
        assertThat(sessionStore.isExpired(NOW.plusSeconds(1800 - 59))).isTrue();
    }

    @Test
    void itStoresNoExpiryWhenTheLoginResponseStatesNone() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("t1", null));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        tokenManager.authenticate();

        assertThat(sessionStore.getExpiresAt()).isEmpty();
        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("t1");
        verify(authGateway, times(1)).login(any(), any());
    }

    @Test
    void itReloginsTransparentlyWhenTheConfiguredUsersPasswordTokenExpired() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.savePassword("user@test.com", "stale", NOW.minusSeconds(1));
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("fresh", 3600));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        assertThat(tokenManager.supportsRefresh()).isTrue();
        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("fresh");
        assertThat(sessionStore.getBearerToken()).contains("fresh");
        assertThat(sessionStore.getExpiresAt()).contains(NOW.plusSeconds(3600));
    }

    @Test
    void itNeverRenewsASessionOpenedByADifferentTypedUser() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.savePassword("other@test.com", "stale", NOW.minusSeconds(1));
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        assertThat(tokenManager.supportsRefresh()).isFalse();
        assertThatThrownBy(tokenManager::ensureValidAccessToken)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("log in again");
        verify(authGateway, never()).login(any(), any());
    }

    @Test
    void anInteractiveLoginTracksExpiryToo() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("typed@test.com", "pw")).thenReturn(new PasswordLogin("typed-token", 900));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        assertThat(tokenManager.loginWithPassword("typed@test.com", "pw")).isEqualTo("typed-token");
        assertThat(sessionStore.getUsername()).contains("typed@test.com");
        assertThat(sessionStore.getExpiresAt()).contains(NOW.plusSeconds(900));
    }

    @Test
    void aLifetimeAtOrBelowTheSkewIsNotTrackedSoTheTokenIsNotBornExpired() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("typed@test.com", "pw"))
            .thenReturn(new PasswordLogin("short-lived", (int) TokenManager.EXPIRY_SKEW.getSeconds()));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        tokenManager.loginWithPassword("typed@test.com", "pw");

        assertThat(sessionStore.getExpiresAt()).isEmpty();
        assertThat(sessionStore.isExpired(NOW)).isFalse();
        // The first call after a successful typed login must not fail as "log in again".
        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("short-lived");
        verify(authGateway, times(1)).login(any(), any());
    }

    @Test
    void aLifetimeJustAboveTheSkewIsTrackedAndValidOnArrival() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        int lifetime = (int) TokenManager.EXPIRY_SKEW.getSeconds() + 1;
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("t1", lifetime));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        tokenManager.authenticate();

        assertThat(sessionStore.getExpiresAt()).contains(NOW.plusSeconds(lifetime));
        assertThat(sessionStore.isExpired(NOW)).isFalse();
        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("t1");
        verify(authGateway, times(1)).login(any(), any());
    }

    @Test
    void aShortLivedClientCredentialsTokenIsNotBornExpiredEither() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        OAuthTokenGateway oauthTokenGateway = mock(OAuthTokenGateway.class);
        ClientCredentialsAuthConfig config = new ClientCredentialsAuthConfig(
            "tenant", null, "client-id", "client-secret", "api://audience", null);
        when(oauthTokenGateway.fetchClientCredentialsToken(config))
            .thenReturn(new OAuthAccessToken("brief", TokenManager.EXPIRY_SKEW.getSeconds()));
        TokenManager tokenManager = new TokenManager(sessionStore, mock(ConnectApiAuthGateway.class),
            oauthTokenGateway, "production", config, CLOCK);

        tokenManager.authenticate();

        assertThat(sessionStore.getExpiresAt()).isEmpty();
        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("brief");
        verify(oauthTokenGateway, times(1)).fetchClientCredentialsToken(any());
    }

    @Test
    void aRenewalKeepsTheUserContextOfTheSamePrincipal() {
        ConnectApiSessionStore sessionStore = new ConnectApiSessionStore();
        sessionStore.savePassword("user@test.com", "stale", NOW.minusSeconds(1));
        com.tsanet.api.connectapi.dto.UserContextDto context =
            new com.tsanet.api.connectapi.dto.UserContextDto(7L, "Acme", 42L, "user@test.com", "user@test.com", "U", "Ser");
        sessionStore.saveUserContext(context);
        ConnectApiAuthGateway authGateway = mock(ConnectApiAuthGateway.class);
        when(authGateway.login("user@test.com", "secret")).thenReturn(new PasswordLogin("fresh", 3600));
        TokenManager tokenManager = new TokenManager(sessionStore, authGateway, mock(OAuthTokenGateway.class),
            "default", new PasswordAuthConfig("user@test.com", "secret"), CLOCK);

        assertThat(tokenManager.ensureValidAccessToken()).isEqualTo("fresh");

        assertThat(sessionStore.getUserContext()).contains(context);
    }
}

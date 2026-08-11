package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        when(authGateway.login("user@test.com", "secret")).thenReturn("password-token");

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
    void itRejectsRefreshForPasswordAuth() {
        TokenManager tokenManager = new TokenManager(
            new ConnectApiSessionStore(),
            mock(ConnectApiAuthGateway.class),
            mock(OAuthTokenGateway.class),
            "default",
            new PasswordAuthConfig("user@test.com", "secret"),
            CLOCK
        );

        assertThatThrownBy(tokenManager::refreshAccessToken)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("client-credentials");
    }
}

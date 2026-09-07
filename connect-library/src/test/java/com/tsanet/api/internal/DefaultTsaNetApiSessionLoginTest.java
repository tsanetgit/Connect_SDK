package com.tsanet.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.connectapi.internal.ConnectApiAttachmentsGateway;
import com.tsanet.api.connectapi.internal.ConnectApiAttachmentsV2Gateway;
import com.tsanet.api.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiFormGateway;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiPartnersGateway;
import com.tsanet.api.connectapi.internal.ConnectApiResponsesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.ConnectApiUserGateway;
import com.tsanet.api.connectapi.internal.ConnectApiWebhooksGateway;
import com.tsanet.api.connectapi.internal.TokenManager;
import com.tsanet.api.storage.AttachmentConfigStorageService;
import com.tsanet.api.storage.AttachmentForwardResultStorageService;
import com.tsanet.api.storage.CaseNoteStorageService;
import com.tsanet.api.storage.CaseResponseStorageService;
import com.tsanet.api.storage.CollaborationRequestFormStorageService;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import com.tsanet.api.storage.PartnerSelectionStorageService;
import com.tsanet.api.storage.UserContextStorageService;
import com.tsanet.api.storage.WebhookInboundEventStorageService;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every login ends with {@code /v1/me}: the context is kept for the session, and a failure
 * there fails the login and clears the store rather than leaving a half-authenticated session.
 */
class DefaultTsaNetApiSessionLoginTest {

    private static final UserContextDto CONTEXT = new UserContextDto(7L, "Acme", 42L, "user@test.com", "user@test.com", "U", "Ser");

    private ConnectApiSessionStore sessionStore;
    private TokenManager tokenManager;
    private ConnectApiUserGateway userGateway;
    private DefaultTsaNetApiSession session;

    @BeforeEach
    void setUp() {
        sessionStore = new ConnectApiSessionStore();
        tokenManager = mock(TokenManager.class);
        userGateway = mock(ConnectApiUserGateway.class);
        TsaNetApiConfiguration configuration = mock(TsaNetApiConfiguration.class);
        when(configuration.auth()).thenReturn(new PasswordAuthConfig("user@test.com", "secret"));
        session = new DefaultTsaNetApiSession(
            configuration,
            sessionStore,
            tokenManager,
            mock(ConnectApiAuthGateway.class),
            mock(ConnectApiCollaborationGateway.class),
            mock(ConnectApiFormGateway.class),
            mock(ConnectApiNotesGateway.class),
            mock(ConnectApiResponsesGateway.class),
            userGateway,
            mock(ConnectApiWebhooksGateway.class),
            mock(ConnectApiPartnersGateway.class),
            mock(ConnectApiAttachmentsGateway.class),
            mock(ConnectApiAttachmentsV2Gateway.class),
            mock(CollaborationRequestStorageService.class),
            mock(CollaborationRequestFormStorageService.class),
            mock(CaseNoteStorageService.class),
            mock(CaseResponseStorageService.class),
            mock(UserContextStorageService.class),
            mock(WebhookSubscriptionStorageService.class),
            mock(WebhookInboundEventStorageService.class),
            mock(PartnerSelectionStorageService.class),
            mock(AttachmentConfigStorageService.class),
            mock(AttachmentForwardResultStorageService.class)
        );
    }

    @Test
    void interactiveLoginFetchesTheCurrentUserAndKeepsTheContext() {
        when(tokenManager.loginWithPassword("user@test.com", "secret")).thenAnswer(inv -> {
            sessionStore.savePassword("user@test.com", "token-1");
            return "token-1";
        });
        when(userGateway.getCurrentUser()).thenReturn(CONTEXT);

        assertThat(session.login("user@test.com", "secret")).isEqualTo("token-1");

        assertThat(session.currentUserContext()).contains(CONTEXT);
        assertThat(session.currentUserContext().map(UserContextDto::companyId)).contains(7L);
        verify(userGateway).getCurrentUser();
    }

    @Test
    void configuredLoginFetchesTheCurrentUserToo() {
        when(tokenManager.authenticate()).thenAnswer(inv -> {
            sessionStore.savePassword("user@test.com", "token-2");
            return "token-2";
        });
        when(userGateway.getCurrentUser()).thenReturn(CONTEXT);

        assertThat(session.authenticate()).isEqualTo("token-2");

        assertThat(session.currentUserContext()).contains(CONTEXT);
    }

    @Test
    void aFailingCurrentUserCallFailsTheLoginAndClearsTheStore() {
        when(tokenManager.loginWithPassword("user@test.com", "secret")).thenAnswer(inv -> {
            sessionStore.savePassword("user@test.com", "token-3");
            return "token-3";
        });
        ConnectApiException forbidden = new ConnectApiException(ConnectApiException.Kind.PROBLEM, 403, "about:blank",
            "Forbidden", "no role", null, null);
        when(userGateway.getCurrentUser()).thenThrow(forbidden);

        assertThatThrownBy(() -> session.login("user@test.com", "secret")).isSameAs(forbidden);

        assertThat(sessionStore.getBearerToken()).isEmpty();
        assertThat(session.currentUserContext()).isEmpty();
        assertThat(session.isAuthorized()).isFalse();
    }

    @Test
    void aClientCredentialsLoginFetchesTheCurrentUserAsWell() {
        // /v1/me answers a client-credentials token (it is not role-gated; BETA probe 2026-09-07),
        // so the machine identity's company is known to the session too.
        TsaNetApiConfiguration oauthConfiguration = mock(TsaNetApiConfiguration.class);
        when(oauthConfiguration.auth()).thenReturn(new ClientCredentialsAuthConfig("tenant", null, "client-id",
            "client-secret", "api://audience", null));
        DefaultTsaNetApiSession oauthSession = new DefaultTsaNetApiSession(
            oauthConfiguration, sessionStore, tokenManager, mock(ConnectApiAuthGateway.class),
            mock(ConnectApiCollaborationGateway.class), mock(ConnectApiFormGateway.class),
            mock(ConnectApiNotesGateway.class), mock(ConnectApiResponsesGateway.class), userGateway,
            mock(ConnectApiWebhooksGateway.class), mock(ConnectApiPartnersGateway.class),
            mock(ConnectApiAttachmentsGateway.class), mock(ConnectApiAttachmentsV2Gateway.class),
            mock(CollaborationRequestStorageService.class), mock(CollaborationRequestFormStorageService.class),
            mock(CaseNoteStorageService.class), mock(CaseResponseStorageService.class),
            mock(UserContextStorageService.class), mock(WebhookSubscriptionStorageService.class),
            mock(WebhookInboundEventStorageService.class), mock(PartnerSelectionStorageService.class),
            mock(AttachmentConfigStorageService.class), mock(AttachmentForwardResultStorageService.class));
        when(tokenManager.authenticate()).thenAnswer(inv -> {
            sessionStore.saveOAuth("production", "oauth-token", java.time.Instant.now().plusSeconds(3600));
            return "oauth-token";
        });
        when(userGateway.getCurrentUser()).thenReturn(CONTEXT);

        assertThat(oauthSession.authenticate()).isEqualTo("oauth-token");

        assertThat(oauthSession.currentUserContext()).contains(CONTEXT);
        assertThat(oauthSession.isAuthorized()).isTrue();
    }

    @Test
    void logoutForgetsTheContext() {
        when(tokenManager.authenticate()).thenAnswer(inv -> {
            sessionStore.savePassword("user@test.com", "token-4");
            return "token-4";
        });
        when(userGateway.getCurrentUser()).thenReturn(CONTEXT);
        session.authenticate();

        session.logout();

        assertThat(session.currentUserContext()).isEmpty();
        verify(tokenManager, never()).refreshAccessToken();
    }
}

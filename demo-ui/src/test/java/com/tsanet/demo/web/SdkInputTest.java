package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.connectapi.CaseNoteValidation;
import com.tsanet.api.connectapi.internal.OAuthTokenGateway;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.WebhooksFacade;
import com.tsanet.demo.config.CredentialsStore;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/**
 * A value the SDK refuses answers 400 with the SDK's message on each route that can reach a validator
 * (tsanetgit/Connect_SDK#157), and nothing else is translated. The facades are mocks, so each test
 * throws what that route's validator throws.
 */
class SdkInputTest {

    private static final String MARKER = "MARKER-not-for-the-caller";
    private static final String UNEXPECTED = "The demo hit an unexpected failure; the server log says where";
    private static final String ACTION = "{\"engineerName\":\"n\",\"engineerEmail\":\"e\",\"text\":\"t\"}";

    private final TsaNetApiSession session = mock(TsaNetApiSession.class);
    private final CaseNotesFacade notes = mock(CaseNotesFacade.class);
    private final CaseResponsesFacade responses = mock(CaseResponsesFacade.class);
    private final PartnersFacade partners = mock(PartnersFacade.class);
    private final AttachmentsFacade attachments = mock(AttachmentsFacade.class);
    private final CollaborationRequestsFacade requests = mock(CollaborationRequestsFacade.class);
    private final WebhooksFacade webhooks = mock(WebhooksFacade.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(session.caseNotes()).thenReturn(notes);
        when(session.caseResponses()).thenReturn(responses);
        when(session.partners()).thenReturn(partners);
        when(session.attachments()).thenReturn(attachments);
        when(session.collaborationRequests()).thenReturn(requests);
        when(session.webhooks()).thenReturn(webhooks);
        SessionGuard guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        mvc = MockMvcBuilders.standaloneSetup(new CollaborationRequestsController(guard),
                new PartnersController(guard), new AttachmentsController(guard), new WebhooksController(guard))
            .setControllerAdvice(new ApiErrorHandler())
            // As CollaborationRequestsControllerTest: standaloneSetup doesn't read application.yml.
            .setMessageConverters(new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build()))
            .build();
    }

    @Test
    void aNoteTheSdkRefusesIsABadRequest() throws Exception {
        String refusal = CaseNoteValidation.validate("s", "x".repeat(CaseNoteValidation.MAX_DESCRIPTION_LENGTH + 1)).message();
        when(notes.createNote(anyString(), anyString(), anyString(), any(), any()))
            .thenThrow(new IllegalArgumentException(refusal));

        mvc.perform(post("/api/requests/tok/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"summary\":\"s\",\"description\":\"d\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Note text exceeds maximum length of 5000 characters."));
    }

    @Test
    void theEngineerActionsAnswerABadRequest() throws Exception {
        when(responses.rejectRequest(anyString(), anyString(), anyString(), any(), anyString()))
            .thenThrow(new IllegalArgumentException("Rejection reason exceeds maximum length of 2000 characters."));
        when(responses.submitInformationRequest(anyString(), anyString(), anyString(), any(), anyString()))
            .thenThrow(new IllegalArgumentException("Requested information exceeds maximum length of 5000 characters."));
        when(responses.submitInformationResponse(anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("Information response exceeds maximum length of 5000 characters."));

        for (String[] route : new String[][] {
            {"reject", "Rejection reason exceeds maximum length of 2000 characters."},
            {"request-info", "Requested information exceeds maximum length of 5000 characters."},
            {"respond-info", "Information response exceeds maximum length of 5000 characters."}}) {
            mvc.perform(post("/api/requests/tok/" + route[0]).contentType(MediaType.APPLICATION_JSON).content(ACTION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(route[1]));
        }
    }

    @Test
    void aPartnerSearchTheSdkRefusesIsABadRequest() throws Exception {
        when(partners.searchPartners("long"))
            .thenThrow(new IllegalArgumentException("Partner search term exceeds maximum length of 100 characters."));

        mvc.perform(get("/api/partners").param("q", "long"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Partner search term exceeds maximum length of 100 characters."));
    }

    @Test
    void anAttachmentTheSdkRefusesIsABadRequest() throws Exception {
        when(attachments.forwardAttachments(anyString(), anyString(), any()))
            .thenThrow(new IllegalArgumentException("Attachment description exceeds maximum length of 500 characters."));

        mvc.perform(multipart("/api/requests/tok/attachments")
                .file(new MockMultipartFile("files", "a.txt", "text/plain", new byte[] {1}))
                .param("description", "d"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Attachment description exceeds maximum length of 500 characters."));
    }

    @Test
    void aCreateTheSdkRefusesIsABadRequestInBothModes() throws Exception {
        String refusal = "Required custom fields are missing values: Product";
        when(requests.createRequest(any(com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto.class),
            anyString(), anyString(), anyString(), any(), anyBoolean())).thenThrow(new IllegalArgumentException(refusal));
        when(requests.createRequest(any(Long.class), anyString(), anyString(), anyString(), anyBoolean()))
            .thenThrow(new IllegalArgumentException(refusal));

        for (String mode : new String[] {"\"formTemplate\":{}", "\"receiverCompanyId\":42"}) {
            mvc.perform(post("/api/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{" + mode + ",\"caseNumber\":\"C-1\",\"summary\":\"s\",\"description\":\"d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(refusal));
        }
    }

    /** What ConnectApiWebhooksGateway.java:59 throws for a mistyped event type on the page's free-text input. */
    @Test
    void aWebhookTheSdkRefusesIsABadRequest() throws Exception {
        when(webhooks.createSubscription(anyString(), any())).thenThrow(new IllegalArgumentException("Unexpected value 'bogus'"));

        mvc.perform(post("/api/webhooks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"callbackUrl\":\"https://x.example/a\",\"eventTypes\":[\"bogus\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Unexpected value 'bogus'"));
    }

    /** What ConnectApiWebhooksGateway throws for a callback URL that isn't one. */
    @Test
    void aBadCallbackUrlIsABadRequestWithItsReason() throws Exception {
        when(webhooks.createSubscription(eq("not a url"), any()))
            .thenThrow(new IllegalArgumentException("The webhook callback URL is not a valid URI"));

        mvc.perform(post("/api/webhooks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"callbackUrl\":\"not a url\",\"eventTypes\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("The webhook callback URL is not a valid URI"));
    }

    /** {@code URI.create(null)} is a NullPointerException, so the SDK never gets the chance to refuse this. */
    @Test
    void aWebhookWithNoCallbackUrlIsABadRequest() throws Exception {
        for (String body : new String[] {"{}", "{\"callbackUrl\":\"  \"}"}) {
            mvc.perform(post("/api/webhooks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("callbackUrl is required"));
        }
    }

    /** Outside a wrapped call the type doesn't help: a route with no validator gets the fixed message. */
    @Test
    void aJdkArgumentFailureOutsideAWrappedCallIsTheFixedMessage() throws Exception {
        Exception fromTheJdk = catchException(() -> java.net.URI.create("https://bad host/" + MARKER));
        when(webhooks.listSubscriptions()).thenThrow(fromTheJdk);

        mvc.perform(get("/api/webhooks"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(UNEXPECTED))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(MARKER))));
    }

    /** What didn't come from a validator isn't the exact type, and its message can quote a value. */
    @Test
    void aSubclassInsideAWrappedCallIsTheFixedMessage() throws Exception {
        when(partners.searchPartners("x")).thenThrow(new NumberFormatException("For input string: " + MARKER));

        mvc.perform(get("/api/partners").param("q", "x"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(UNEXPECTED));
    }

    @Test
    void sdkInputPassesEverythingElseThrough() {
        assertThat(SessionGuard.sdkInput(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> SessionGuard.sdkInput(() -> {
            throw new IllegalStateException(MARKER);
        })).isExactlyInstanceOf(IllegalStateException.class);
    }

    /** What the page reads as "Auth failed: ...", from the real throw site against a stubbed 401. */
    @Test
    void aRefusedSignInKeepsItsReasonAt502() throws Exception {
        HttpServer entra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        entra.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        entra.start();
        Exception refused;
        try {
            String tokenUrl = "http://127.0.0.1:" + entra.getAddress().getPort() + "/token";
            refused = catchException(() -> new OAuthTokenGateway(HttpClient.newHttpClient())
                .fetchClientCredentialsToken(new ClientCredentialsAuthConfig(
                    null, tokenUrl, "client-id", "client-secret", "api://audience", null)));
        } finally {
            entra.stop(0);
        }

        EnvironmentService environments = mock(EnvironmentService.class);
        CredentialsStore store = mock(CredentialsStore.class);
        CredentialsStore.Credentials credentials = new CredentialsStore.Credentials("password", "u", "p", null, null);
        AuthFacade auth = mock(AuthFacade.class);
        when(environments.definitionFor("beta"))
            .thenReturn(new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null));
        when(environments.credentialsFor("beta")).thenReturn(store);
        when(store.load()).thenReturn(Optional.of(credentials));
        when(environments.sessionFor("beta", credentials)).thenReturn(session);
        when(session.auth()).thenReturn(auth);
        org.mockito.Mockito.doThrow(refused).when(auth).authenticate();

        assertThatThrownBy(() -> new SessionGuard(environments).session("beta"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(e.getReason()).isEqualTo("OAuth client-credentials request failed: HTTP 401");
            });

        // A subclass didn't come from the SDK's sign-in, so it stays for the catch-all's fixed message.
        org.mockito.Mockito.doThrow(new java.util.concurrent.CancellationException(MARKER)).when(auth).authenticate();
        assertThatThrownBy(() -> new SessionGuard(environments).session("beta"))
            .isExactlyInstanceOf(java.util.concurrent.CancellationException.class);
    }
}

package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.generated.api.CaseResponsesApi;
import com.tsanet.api.generated.api.IdentityApi;
import com.tsanet.api.generated.invoker.ApiClient;
import com.tsanet.api.generated.model.LoginRequestDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The classification, driven through the generated client on the library's own
 * {@link RestTemplate} so what is tested is what production runs. The shapes are the ones
 * recorded on tsanetgit/Connect-API-Code#122 (2026-07-10): problem+json with the documented
 * 4xx when the client sends the Accept header the library sends, and the legacy 500 body.
 * Every request path carries a marker token, and no message may carry it back.
 */
class ConnectApiResponseErrorHandlerTest {

    private static final String BASE = "https://connect.example";
    private static final String TOKEN = "CASETOKEN-MARKER";

    private MockRestServiceServer server;
    private CaseResponsesApi requests;
    private IdentityApi identity;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-1");
        requests = new CaseResponsesApi(apiClient);
        identity = new IdentityApi(apiClient);
    }

    @Test
    void aProblemDetailsAnswerBecomesATypedProblemWithTheApisOwnWords() {
        server.expect(requestTo(BASE + "/v1/collaboration-requests/" + TOKEN + "/closure"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"type\":\"https://connect.example/problems/case-update-error\",\"title\":\"Unprocessable Entity\","
                    + "\"status\":422,\"detail\":\"OPEN cases cannot be closed.\",\"instance\":\"/v1/collaboration-requests/" + TOKEN + "/closure\"}"));

        assertThatThrownBy(() -> requests.closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.PROBLEM);
                assertThat(ex.status()).isEqualTo(422);
                assertThat(ex.title()).isEqualTo("Unprocessable Entity");
                assertThat(ex.detail()).isEqualTo("OPEN cases cannot be closed.");
                assertThat(ex.isProblem("case-update-error")).isTrue();
                assertThat(ex.getMessage()).isEqualTo("HTTP 422 Unprocessable Entity (case-update-error): OPEN cases cannot be closed.");
                assertThat(ex.getMessage()).doesNotContain("MARKER");
            });
    }

    @Test
    void theLegacyFiveHundredWithAMessageBodyIsClassifiedByTheBodyNotTheStatus() {
        server.expect(requestTo(BASE + "/v1/collaboration-requests/" + TOKEN + "/closure"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"OPEN cases cannot be closed.\"}"));

        assertThatThrownBy(() -> requests.closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.LEGACY);
                assertThat(ex.status()).isEqualTo(500);
                assertThat(ex.detail()).isEqualTo("OPEN cases cannot be closed.");
                assertThat(ex.getMessage()).isEqualTo("HTTP 500 (legacy error): OPEN cases cannot be closed.");
            });
    }

    @Test
    void legacyValidationErrorsAreFoldedIntoTheDetail() {
        server.expect(requestTo(BASE + "/v1/collaboration-requests/" + TOKEN + "/closure"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Validation failed\",\"validationErrors\":[{\"field\":\"engineerEmail\",\"message\":\"must be on the member domain\"}]}"));

        assertThatThrownBy(() -> requests.closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> assertThat(((ConnectApiException) e).detail())
                .isEqualTo("Validation failed [engineerEmail: must be on the member domain]"));
    }

    @Test
    void anUnrecognizedBodyYieldsTheStatusAndAShortExcerptAndNeverTheUrl() {
        server.expect(requestTo(BASE + "/v1/collaboration-requests/" + TOKEN + "/closure"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML)
                .body("<html><body>  gateway   timeout   </body></html>"));

        assertThatThrownBy(() -> requests.closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.OTHER);
                assertThat(ex.status()).isEqualTo(502);
                assertThat(ex.detail()).isEqualTo("<html><body> gateway timeout </body></html>");
                assertThat(ex.getMessage()).doesNotContain("MARKER").doesNotContain("connect.example");
            });
    }

    @Test
    void aBadLoginIsAFourOhOneProblemNotAServerError() {
        server.expect(requestTo(BASE + "/v1/login"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"type\":\"https://connect.example/problems/authentication-error\",\"title\":\"Authentication Failed\",\"status\":401,\"detail\":\"Bad credentials\"}"));

        assertThatThrownBy(() -> identity.login(new LoginRequestDTO().username("u").password("p")))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.status()).isEqualTo(401);
                assertThat(ex.isProblem("authentication-error")).isTrue();
                assertThat(ex.getMessage()).startsWith("HTTP 401 Authentication Failed");
            });
    }

    @Test
    void aTransportFailureIsClassifiedAsConnectivityWithoutTheUrl() {
        ApiClient dead = new ApiClient(ConnectApiRestTemplates.create());
        dead.setBasePath("http://127.0.0.1:1");
        dead.setBearerToken(() -> "bearer-1");

        assertThatThrownBy(() -> new CaseResponsesApi(dead).closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.CONNECTIVITY);
                assertThat(ex.status()).isZero();
                assertThat(ex.detail()).isNotBlank();
                assertThat(ex.getMessage()).doesNotContain("MARKER").doesNotContain("127.0.0.1");
            });
    }

    @Test
    void anIntermediaryErrorPageThatEchoesTheRequestPathLosesItBeforeTheExcerpt() {
        server.expect(requestTo(BASE + "/v1/collaboration-requests/" + TOKEN + "/closure"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML)
                .body("<html><body>The requested URL /v1/collaboration-requests/" + TOKEN + "/closure was not found on this server.</body></html>"));

        assertThatThrownBy(() -> requests.closeCollaborationRequest(TOKEN))
            .isInstanceOf(ConnectApiException.class)
            .satisfies(e -> {
                ConnectApiException ex = (ConnectApiException) e;
                assertThat(ex.kind()).isEqualTo(ConnectApiException.Kind.OTHER);
                assertThat(ex.detail()).contains("<request-path>").doesNotContain("MARKER");
                assertThat(ex.getMessage()).doesNotContain("MARKER");
            });
    }

    @Test
    void classificationIsByBodyFirstEvenWhenAProblemBodyRidesOnAFiveHundred() {
        ConnectApiResponseErrorHandler handler = new ConnectApiResponseErrorHandler();
        ConnectApiException problemOn500 = handler.classify(500,
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Only the receiving partner can approve a case.\"}");
        assertThat(problemOn500.kind()).isEqualTo(ConnectApiException.Kind.PROBLEM);
        assertThat(problemOn500.status()).isEqualTo(403);
        assertThat(handler.classify(404, "").kind()).isEqualTo(ConnectApiException.Kind.OTHER);
        assertThat(handler.classify(404, "").detail()).isNull();
        assertThat(handler.classify(503, "not json at all").detail()).isEqualTo("not json at all");
    }
}

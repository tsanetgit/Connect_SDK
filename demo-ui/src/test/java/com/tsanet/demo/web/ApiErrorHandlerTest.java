package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.ConnectApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;

/** The SDK's classified answer reaches the page with the API's own words and the upstream status. */
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    @Test
    void aProblemPassesItsStatusTitleDetailAndTypeThrough() {
        ConnectApiException e = new ConnectApiException(ConnectApiException.Kind.PROBLEM, 422,
            "https://connect.example/problems/case-update-error", "Unprocessable Entity", "OPEN cases cannot be closed.", null, null);

        ResponseEntity<Map<String, String>> response = handler.handleConnectApi(e);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).containsEntry("error", "Unprocessable Entity")
            .containsEntry("detail", "OPEN cases cannot be closed.")
            .containsEntry("type", "https://connect.example/problems/case-update-error")
            .containsEntry("kind", "PROBLEM");
    }

    @Test
    void aLegacyAnswerKeepsItsStatusAndMessage() {
        ConnectApiException e = new ConnectApiException(ConnectApiException.Kind.LEGACY, 500, null, null,
            "Only the receiving partner can approve a case.", null, null);

        ResponseEntity<Map<String, String>> response = handler.handleConnectApi(e);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsEntry("error", e.getMessage())
            .containsEntry("detail", "Only the receiving partner can approve a case.")
            .doesNotContainKey("type");
    }

    @Test
    void connectivityIsABadGateway() {
        ConnectApiException e = ConnectApiException.connectivity(new java.net.ConnectException("refused"));

        ResponseEntity<Map<String, String>> response = handler.handleConnectApi(e);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).containsEntry("kind", "CONNECTIVITY")
            .containsEntry("detail", "ConnectException");
    }

    /** The one status rule: a real platform status passes through; anything else is a 502. */
    @Test
    void upstreamStatusPassesARealPlatformStatusAndMakesEverythingElseABadGateway() {
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        expected.put(400, 400);
        expected.put(401, 401);
        expected.put(499, 499);
        expected.put(500, 500);
        expected.put(599, 599);
        // No platform status: none at all, not an error, or not an HTTP status.
        expected.put(-1, 502);
        expected.put(0, 502);
        expected.put(200, 502);
        expected.put(600, 502);
        expected.put(700, 502);
        expected.put(1000, 502);
        expected.forEach((status, answer) -> assertThat(ApiErrorHandler.upstreamStatus(false, status).value())
            .as("status %s", status).isEqualTo(answer));
        assertThat(ApiErrorHandler.upstreamStatus(true, 404).value()).as("unreachable").isEqualTo(502);
    }

    /** What the page reads as "Auth failed: ..." when the token endpoint refuses the credentials. */
    @Test
    void aMessageTheSdkOrTheDemoWroteItselfStillReachesThePage() {
        ResponseEntity<Map<String, String>> state = handler.handleOther(thrownHere("OAuth client-credentials request failed: HTTP 401"));
        ResponseEntity<Map<String, String>> argument = handler.handleOther(thrownHereArgument("fileName must not be blank"));

        assertThat(state.getStatusCode().value()).isEqualTo(502);
        assertThat(state.getBody()).containsEntry("error", "OAuth client-credentials request failed: HTTP 401");
        assertThat(argument.getStatusCode().value()).isEqualTo(502);
        assertThat(argument.getBody()).containsEntry("error", "fileName must not be blank");
    }

    /** The real throw site, not a stand-in: its top frame is what lets the reason through. */
    @Test
    void aRefusedTokenRequestKeepsItsReasonForThePage() throws Exception {
        com.sun.net.httpserver.HttpServer entra =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        entra.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        entra.start();
        try {
            String tokenUrl = "http://127.0.0.1:" + entra.getAddress().getPort() + "/token";
            Exception refused = org.assertj.core.api.Assertions.catchException(() ->
                new com.tsanet.api.connectapi.internal.OAuthTokenGateway(java.net.http.HttpClient.newHttpClient())
                    .fetchClientCredentialsToken(new com.tsanet.api.auth.ClientCredentialsAuthConfig(
                        null, tokenUrl, "client-id", "client-secret", "api://audience", null)));

            assertThat(handler.handleOther(refused).getBody())
                .containsEntry("error", "OAuth client-credentials request failed: HTTP 401");
        } finally {
            entra.stop(0);
        }
    }

    /** The same types from a JDK or framework frame carry whatever they were handed, so they don't pass. */
    @Test
    void aJdkMessageIsNotOurs(CapturedOutput output) {
        Exception fromTheJdk = null;
        try {
            java.net.URI.create("https://bad host/" + MARKER);
        } catch (IllegalArgumentException e) {
            fromTheJdk = e;
        }

        assertThat(fromTheJdk).hasMessageContaining(MARKER);
        assertUnexpected(handler.handleOther(fromTheJdk));
        assertThat(output).doesNotContain(MARKER);
    }

    @Test
    void anUnexpectedFailureAnswersAFixedMessageAndLogsNoValue(CapturedOutput output) {
        RuntimeException failure = new java.io.UncheckedIOException(MARKER, new java.io.IOException(MARKER));

        assertUnexpected(handler.handleOther(failure));

        assertThat(output).contains("java.io.UncheckedIOException", "java.io.IOException", "ApiErrorHandlerTest")
            .doesNotContain(MARKER);
    }

    @Test
    void aMessagelessFailureOfOurOwnIsStillAFixedMessage() {
        assertUnexpected(handler.handleOther(thrownHere(null)));
    }

    /** Spring's own status for a request the size limit stopped, which the app's 20 GB limit makes slow to reach. */
    @Test
    void anOversizedUploadKeepsItsStatus() throws Exception {
        @org.springframework.web.bind.annotation.RestController
        class Oversized {
            @org.springframework.web.bind.annotation.GetMapping("/oversized")
            void fail() {
                throw new org.springframework.web.multipart.MaxUploadSizeExceededException(1);
            }
        }
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Oversized())
            .setControllerAdvice(handler).build()
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/oversized"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isPayloadTooLarge());
    }

    @Test
    void aCauseChainThatIsACycleStillAnswers() {
        RuntimeException first = new RuntimeException(MARKER);
        RuntimeException second = new RuntimeException(MARKER, first);
        first.initCause(second);

        assertUnexpected(handler.handleOther(first));
    }

    private static final String MARKER = "MARKER-not-for-the-caller";

    private static IllegalStateException thrownHere(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalArgumentException thrownHereArgument(String message) {
        return new IllegalArgumentException(message);
    }

    private static void assertUnexpected(ResponseEntity<Map<String, String>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("error")).isEqualTo("The demo hit an unexpected failure; the server log says where")
            .doesNotContain(MARKER);
    }
}

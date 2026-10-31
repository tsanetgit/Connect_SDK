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

    /** A JDK exception carries whatever it was handed, so its message stays off the page and out of the log. */
    @Test
    void aJdkMessageReachesNeitherThePageNorTheLog(CapturedOutput output) {
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

    private static void assertUnexpected(ResponseEntity<Map<String, String>> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody().get("error")).isEqualTo("The demo hit an unexpected failure; the server log says where")
            .doesNotContain(MARKER);
    }
}

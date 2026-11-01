package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A CSRF refusal over a real connection. MockMvc doesn't perform the container's error dispatch, so
 * only this shows the refusal stays a 403 instead of becoming the /error dispatch's 401, whose
 * {@code WWW-Authenticate} would make the browser prompt for the login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "tsanet.demo.auth.username=shawn",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-csrf-test-${random.uuid}"
})
class CsrfOverHttpTest {

    private static final String LOGIN = "Basic " + Base64.getEncoder().encodeToString("shawn:op".getBytes(StandardCharsets.UTF_8));

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", LOGIN);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String csrfToken(HttpResponse<?> response) {
        String cookie = response.headers().allValues("Set-Cookie").stream()
            .filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        return cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';'));
    }

    @Test
    void aRefusalIsA403ThePageCanReadAndDoesNotPromptForTheLogin() throws Exception {
        String token = csrfToken(send(request("/api/settings").GET().build()));

        HttpResponse<String> refused = send(request("/api/requests/t1/close")
            .header("Cookie", "XSRF-TOKEN=" + token)
            .POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(refused.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(refused.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(refused.body()).isEqualTo(SecurityConfig.CSRF_REFUSED);
    }

    @Test
    void aMissingCookieIsRefusedAndTheRefusalIssuesOneSoARetryWorks() throws Exception {
        HttpResponse<String> refused = send(request("/api/requests/t1/close")
            .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(refused.statusCode()).isEqualTo(403);

        String token = csrfToken(refused);
        assertThat(send(request("/api/requests/t1/close")
            .header("Cookie", "XSRF-TOKEN=" + token)
            .header("X-XSRF-TOKEN", token)
            .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode()).isEqualTo(428);
    }

    @Test
    void theTokenStaysGoodAcrossBasicAuthRequests() throws Exception {
        String token = csrfToken(send(request("/api/settings").GET().build()));
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> passed = send(request("/api/requests/t1/close")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
            assertThat(passed.statusCode()).isEqualTo(428);
            assertThat(passed.headers().allValues("Set-Cookie")).noneMatch(value -> value.startsWith("XSRF-TOKEN=;"));
        }
    }
}

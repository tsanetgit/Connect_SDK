package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.connectapi.internal.ConnectApiRestTemplates;
import com.tsanet.api.generated.api.WebhooksApi;
import com.tsanet.api.generated.invoker.ApiClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * What a session opened through {@link TsaNetApi#initialize} puts in {@code Accept}, on the wire,
 * for its {@code /v1/} calls (tsanetgit/Connect_SDK#127). The local server answers a failure the
 * way the API's {@code ResponseFormatResolver} does: problem details only when the request's
 * {@code Accept} contains {@code application/problem+json}, otherwise the legacy
 * {@code {"message"}} body. The login and session clients are both built by
 * {@code ConnectApiRestTemplates.create()}, so this covers each.
 */
class TsaNetApiAcceptHeaderWiringTest {

    private static final String DUAL_ACCEPT = "application/json, application/problem+json";
    private static final String TOKEN = "case-token-1";
    private static final String RETRY_TOKEN = "case-token-retry";
    private static final String GOOD_PASSWORD = "test-password";

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<List<String>> acceptHeaders = new CopyOnWriteArrayList<>();
    private final List<String> retriedNoteBodies = new CopyOnWriteArrayList<>();
    private final List<String> retriedNoteFraming = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private HttpServer server;
    private String base;

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(request);
        List<String> accept = exchange.getRequestHeaders().get("Accept");
        acceptHeaders.add(accept == null ? List.of() : List.copyOf(accept));
        boolean problem = accept != null && accept.stream().anyMatch(a -> a.contains("application/problem+json"));
        switch (request) {
            case "POST /v1/login" -> {
                if (body.contains("\"" + GOOD_PASSWORD + "\"")) {
                    respond(exchange, 200, "application/json", "{\"accessToken\":\"bearer-" + logins.incrementAndGet()
                        + "\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
                } else if (problem) {
                    respond(exchange, 401, "application/problem+json",
                        "{\"type\":\"https://api.tsanet.org/errors/authentication-error\",\"title\":\"Authentication Failed\","
                            + "\"status\":401,\"detail\":\"Authentication attempt failed.\"}");
                } else {
                    respond(exchange, 500, "application/json", "{\"message\":\"Error processing request\"}");
                }
            }
            case "GET /v1/me" -> respond(exchange, 200, "application/json",
                "{\"company\":{\"id\":303,\"name\":\"Sender\"},"
                    + "\"user\":{\"id\":1,\"username\":\"sender@example.test\",\"email\":\"sender@example.test\"}}");
            case "GET /v1/note-types" -> respond(exchange, 200, "application/json",
                "[{\"code\":\"partner\",\"displayName\":\"Partner\"}]");
            case "POST /v1/collaboration-requests/" + TOKEN + "/notes" -> {
                if (problem) {
                    respond(exchange, 400, "application/problem+json",
                        "{\"type\":\"https://api.tsanet.org/errors/invalid-note-type\",\"title\":\"Invalid Note Type\","
                            + "\"status\":400,\"detail\":\"Invalid note type 'bogus'. Allowed values: partner\"}");
                } else {
                    respond(exchange, 400, "application/json",
                        "{\"message\":\"Validation failed\",\"validationErrors\":[{\"field\":\"type\","
                            + "\"message\":\"Invalid note type 'bogus'. Allowed values: partner\"}]}");
                }
            }
            case "POST /v1/collaboration-requests/" + RETRY_TOKEN + "/notes" -> {
                // The first login's bearer is refused, as an expired one would be; the re-login's is accepted.
                retriedNoteBodies.add(body);
                retriedNoteFraming.add("Content-Length=" + exchange.getRequestHeaders().getFirst("Content-Length")
                    + " Transfer-Encoding=" + exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
                if ("Bearer bearer-1".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, "application/problem+json",
                        "{\"type\":\"https://api.tsanet.org/errors/authentication-error\",\"title\":\"Authentication Failed\","
                            + "\"status\":401,\"detail\":\"Token expired.\"}");
                } else {
                    respond(exchange, 200, "application/json", "{\"id\":5,\"token\":\"n5\",\"type\":null}");
                }
            }
            case "GET /v1/collaboration-requests/" + RETRY_TOKEN + "/notes" -> respond(exchange, 200, "application/json", "[]");
            case "DELETE /v1/webhooks/7" -> exchange.sendResponseHeaders(204, -1);
            default -> respond(exchange, 404, "application/json", "{\"message\":\"unexpected " + request + "\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private TsaNetApiSession session() {
        return TsaNetApi.initialize(new TsaNetApiConfiguration(base, tmp.resolve("cache.db").toString(), "default",
            new PasswordAuthConfig("sender@example.test", GOOD_PASSWORD), Set.of()));
    }

    @Test
    void theLoginAndSessionClientsBothSendJsonAndProblemJsonAsOneAcceptHeader() {
        TsaNetApiSession session = session();
        session.auth().login("sender@example.test", GOOD_PASSWORD);
        session.caseNotes().listNoteTypes();

        assertThat(requests).contains("POST /v1/login", "GET /v1/note-types");
        assertThat(acceptHeaders).hasSameSizeAs(requests)
            .allSatisfy(accept -> assertThat(accept).containsExactly(DUAL_ACCEPT));
    }

    @Test
    void anOperationWithNoResponseBodySendsTheSameAccept() {
        ApiClient apiClient = new ApiClient(ConnectApiRestTemplates.create());
        apiClient.setBasePath(base);
        apiClient.setBearerToken(() -> "bearer-1");

        new WebhooksApi(apiClient).deleteWebhookSubscription(7L);

        assertThat(requests).containsExactly("DELETE /v1/webhooks/7");
        assertThat(acceptHeaders).containsExactly(List.of(DUAL_ACCEPT));
    }

    @Test
    void anAcceptOtherThanJsonAloneIsLeftAsSet() {
        // Every generated operation asks for application/json today; a request that asks for
        // anything else keeps its own Accept.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_PLAIN));

        ConnectApiRestTemplates.create()
            .exchange(base + "/v1/note-types", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(acceptHeaders).containsExactly(List.of("text/plain"));
    }

    @Test
    void aBadLoginIsAFourOhOneNotTheLegacyFiveHundred() {
        TsaNetApiSession session = session();

        // A 401 answer to a POST keeps its body (tsanetgit/Connect_SDK#128): the old
        // HttpURLConnection transport dropped it and this read "HTTP 401 from the Connect API".
        assertThatThrownBy(() -> session.auth().login("sender@example.test", "wrong"))
            .isInstanceOfSatisfying(ConnectApiException.class, e -> {
                assertThat(e.status()).isEqualTo(401);
                assertThat(e.kind()).isEqualTo(ConnectApiException.Kind.PROBLEM);
                assertThat(e.isProblem("authentication-error")).isTrue();
                assertThat(e.getMessage())
                    .isEqualTo("HTTP 401 Authentication Failed (authentication-error): Authentication attempt failed.");
            });
        assertThat(acceptHeaders).containsExactly(List.of(DUAL_ACCEPT));
    }

    @Test
    void aFourOhOneToAPostIsResentWithTheSameBodyAfterTheReLogin() {
        TsaNetApiSession session = session();
        session.auth().login("sender@example.test", GOOD_PASSWORD);

        session.caseNotes().createNote(RETRY_TOKEN, "retry summary", "retry description", "LOW", "partner");

        assertThat(logins).hasValue(2);
        assertThat(retriedNoteBodies).hasSize(2);
        assertThat(retriedNoteBodies.get(0)).contains("retry summary").isEqualTo(retriedNoteBodies.get(1));
        // A fixed length on both sends, not chunked.
        String framing = "Content-Length=" + retriedNoteBodies.get(0).getBytes(StandardCharsets.UTF_8).length
            + " Transfer-Encoding=null";
        assertThat(retriedNoteFraming).containsExactly(framing, framing);
    }

    @Test
    void aBadNoteCodeIsAProblemNotTheLegacyBody() {
        TsaNetApiSession session = session();
        session.auth().login("sender@example.test", GOOD_PASSWORD);

        assertThatThrownBy(() -> session.caseNotes().createNote(TOKEN, "s", "d", "LOW", "bogus"))
            .isInstanceOfSatisfying(ConnectApiException.class, e -> {
                assertThat(e.kind()).isEqualTo(ConnectApiException.Kind.PROBLEM);
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.isProblem("invalid-note-type")).isTrue();
                assertThat(e.detail()).isEqualTo("Invalid note type 'bogus'. Allowed values: partner");
            });
    }
}

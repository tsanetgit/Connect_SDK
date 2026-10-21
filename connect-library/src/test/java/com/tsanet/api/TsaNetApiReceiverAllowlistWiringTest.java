package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session opened through {@link TsaNetApi#initialize} carries its configuration's receiver
 * allowlist into its V2 client. The gateway tests build the gateway directly, so they can't see
 * the runtime dropping the list; this drives a real session against a local server instead.
 */
class TsaNetApiReceiverAllowlistWiringTest {

    private static final String TOKEN = "case-token-1";
    private static final String CASE_READ = "GET /v1/collaboration-requests/" + TOKEN;
    private static final String GRANT_CREATE = "POST /v2/collaboration-requests/" + TOKEN + "/attachments/grants";

    private final List<String> requests = new CopyOnWriteArrayList<>();
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
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(request);
        switch (request) {
            case "POST /v1/login" -> respond(exchange, 200, "application/json",
                "{\"accessToken\":\"bearer-1\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
            case "GET /v1/me" -> respond(exchange, 200, "application/json",
                "{\"company\":{\"id\":303,\"name\":\"Sender\"},"
                    + "\"user\":{\"id\":1,\"username\":\"sender@example.test\",\"email\":\"sender@example.test\"}}");
            case CASE_READ -> respond(exchange, 200, "application/json",
                "{\"id\":77,\"token\":\"" + TOKEN + "\",\"receiveCompanyId\":202,\"submitCompanyId\":303,"
                    + "\"direction\":\"OUTBOUND\"}");
            default -> respond(exchange, 403, "application/problem+json",
                "{\"type\":\"https://api.tsanet.org/errors/access-denied\",\"title\":\"Forbidden\",\"status\":403}");
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

    /** A logged-in session from a configuration carrying {@code allowlist}; the login's requests are forgotten. */
    private TsaNetApiSession session(Set<Long> allowlist) {
        TsaNetApiSession session = TsaNetApi.initialize(new TsaNetApiConfiguration(base,
            tmp.resolve("cache.db").toString(), "default", new PasswordAuthConfig("sender@example.test", "pw"), allowlist));
        session.auth().login("sender@example.test", "pw");
        requests.clear();
        return session;
    }

    @Test
    void aConfiguredAllowlistReachesTheV2ClientAndRefusesBeforeAnyGrantRequest() {
        TsaNetApiSession session = session(Set.of(101L));

        assertThatThrownBy(() -> session.attachmentsV2().createGrant(TOKEN, "diag.log", 12))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.RECEIVER_NOT_ALLOWED));
        assertThat(requests).containsExactly(CASE_READ);
    }

    @Test
    void withNoAllowlistTheCaseIsNotReadAndTheGrantRequestGoesOut() {
        TsaNetApiSession session = session(Set.of());

        assertThatThrownBy(() -> session.attachmentsV2().createGrant(TOKEN, "diag.log", 12))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.FORBIDDEN));
        assertThat(requests).containsExactly(GRANT_CREATE);
    }
}

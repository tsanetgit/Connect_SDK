package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A token response is sent back as a request header on every later call, and the JDK's header check
 * quotes a value it refuses. These run through a real session and a real JDK client, because the
 * leak is in that check, not in anything a mock would reach.
 */
class TsaNetApiTokenHeaderSafetyTest {
    private static final String MARKER = "MARKER";
    private static final String REFUSAL = "The token response holds an access token that can't be sent in a header";

    private HttpServer server;
    private String base;
    private final AtomicInteger meCalls = new AtomicInteger();
    private volatile String tokenBody;
    private volatile int otherStatus = 200;
    private volatile String authorizationOnMe;

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
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/v1/login") || path.equals("/token")) {
            respond(exchange, 200, tokenBody);
        } else if (path.equals("/v1/me")) {
            meCalls.incrementAndGet();
            authorizationOnMe = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, 200, "{\"company\":{\"id\":303,\"name\":\"Sender\"},"
                + "\"user\":{\"id\":1,\"username\":\"sender@example.test\",\"email\":\"sender@example.test\"}}");
        } else {
            respond(exchange, otherStatus, "[]");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private TsaNetApiSession session(AccountAuthConfig auth) {
        return TsaNetApi.initialize(new TsaNetApiConfiguration(base, tmp.resolve("cache.db").toString(), "default",
            auth, Set.of()));
    }

    private TsaNetApiSession passwordSession() {
        return session(new PasswordAuthConfig("sender@example.test", "a-password"));
    }

    private TsaNetApiSession oauthSession() {
        return session(new ClientCredentialsAuthConfig("tenant", base + "/token", "client-id", "client-secret",
            "api://audience", null));
    }

    private static void assertNoMarkerInTheChain(Throwable thrown) {
        assertThat(thrown).isNotNull();
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(MARKER);
            assertThat(t.toString()).doesNotContain(MARKER);
        }
    }

    /** JSON-escaped values: each is a token the JDK refuses, or that isn't a token at all. */
    @ParameterizedTest
    @ValueSource(strings = {
        "good\\r\\nMARKER", "good\\nMARKER", "good\\rMARKER", "good\\tMARKER", "good\\u0000MARKER", "good\\u007fMARKER",
        "good\\u0085MARKER", "good\\u2028MARKER", "good\\u20acMARKER", "good MARKER"
    })
    void aPasswordLoginRefusesATokenThatCantBeAHeader(String escaped) {
        tokenBody = "{\"accessToken\":\"" + escaped + "\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
        TsaNetApiSession session = passwordSession();

        Throwable thrown = catchThrowable(() -> session.auth().authenticate());

        assertThat(thrown).isExactlyInstanceOf(ConnectApiException.class).hasMessage(REFUSAL).hasNoCause();
        assertNoMarkerInTheChain(thrown);
        assertThat(meCalls).as("nothing was sent with it").hasValue(0);
        assertThat(session.auth().isAuthorized()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "good\\r\\nMARKER", "good\\nMARKER", "good\\rMARKER", "good\\tMARKER", "good\\u0000MARKER", "good\\u007fMARKER",
        "good\\u0085MARKER", "good\\u2028MARKER", "good\\u20acMARKER", "good MARKER"
    })
    void aClientCredentialsLoginRefusesATokenThatCantBeAHeader(String escaped) {
        tokenBody = "{\"access_token\":\"" + escaped + "\",\"expires_in\":3600}";
        TsaNetApiSession session = oauthSession();

        Throwable thrown = catchThrowable(() -> session.auth().authenticate());

        assertThat(thrown).isExactlyInstanceOf(ConnectApiException.class).hasMessage(REFUSAL).hasNoCause();
        assertNoMarkerInTheChain(thrown);
        assertThat(meCalls).as("nothing was sent with it").hasValue(0);
        assertThat(session.auth().isAuthorized()).isFalse();
    }

    @Test
    void aMalformedPasswordTokenResponseDoesNotQuoteItsBody() {
        tokenBody = "{\"accessToken\":MARKERtoken}";
        Throwable thrown = catchThrowable(() -> passwordSession().auth().authenticate());
        assertThat(thrown).hasMessage("Failed to parse login response").hasNoCause();
        assertNoMarkerInTheChain(thrown);
    }

    @Test
    void aMalformedClientCredentialsTokenResponseDoesNotQuoteItsBody() {
        tokenBody = "{\"access_token\":MARKERtoken}";
        Throwable thrown = catchThrowable(() -> oauthSession().auth().authenticate());
        assertThat(thrown).hasMessage("Failed to parse OAuth token response").hasNoCause();
        assertNoMarkerInTheChain(thrown);
    }

    @Test
    void aLoginResponseTheClientCantReadIsAFixedMessage() throws Exception {
        try (java.net.ServerSocket endpoint = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread answer = Thread.ofVirtual().start(() -> {
                try (java.net.Socket client = endpoint.accept()) {
                    client.getInputStream().readNBytes(1);
                    client.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: MARKER\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    Thread.sleep(200);
                } catch (Exception ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            base = "http://127.0.0.1:" + endpoint.getLocalPort();
            TsaNetApiSession session = passwordSession();

            Throwable thrown = catchThrowable(() -> session.auth().authenticate());

            assertThat(thrown).isExactlyInstanceOf(ConnectApiException.class)
                .hasMessage("The login request failed: its URL or the response isn't usable")
                .hasNoCause();
            answer.join(java.time.Duration.ofSeconds(2));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://bad host", "http://[x", "ftp://x"})
    void aBaseUrlTheClientWontAcceptIsNotReportedAsABadResponseAlone(String apiBaseUrl) {
        base = apiBaseUrl;
        TsaNetApiSession session = passwordSession();

        Throwable thrown = catchThrowable(() -> session.auth().authenticate());

        assertThat(thrown).isExactlyInstanceOf(ConnectApiException.class)
            .hasMessage("The login request failed: its URL or the response isn't usable")
            .hasNoCause();
    }

    @Test
    void aRenewalAfterA401RefusesTheNewTokenInsteadOfSendingIt() {
        tokenBody = "{\"accessToken\":\"good\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
        TsaNetApiSession session = passwordSession();
        session.auth().authenticate();
        tokenBody = "{\"accessToken\":\"good\\r\\nMARKER\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
        otherStatus = 401;

        Throwable thrown = catchThrowable(() -> session.webhooks().listSubscriptions());

        assertNoMarkerInTheChain(thrown);
        assertThat(thrown).hasMessageContaining(REFUSAL);
    }

    /** A case token is a capability, and it goes in the path: the generated client encodes it. */
    @Test
    void aCaseTokenWithALineBreakDoesNotReachAMessage() {
        tokenBody = "{\"accessToken\":\"good\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
        TsaNetApiSession session = passwordSession();
        session.auth().authenticate();
        otherStatus = 404;

        Throwable thrown = catchThrowable(() -> session.caseNotes().listNotesForRequest("tok\r\nMARKER"));

        assertNoMarkerInTheChain(thrown);
    }

    @Test
    void aTokenOfEveryVisibleAsciiCharacterIsAcceptedAndSentIntact() {
        tokenBody = "{\"access_token\":\"eyJhbGci.OiJ-SUz_~+/=!#$%&'()*,:;<>?@[]^`{|}\\\"\\\\\",\"expires_in\":3600}";
        TsaNetApiSession session = oauthSession();

        session.auth().authenticate();

        assertThat(session.auth().isAuthorized()).isTrue();
        assertThat(meCalls).hasValue(1);
        assertThat(authorizationOnMe).isEqualTo("Bearer eyJhbGci.OiJ-SUz_~+/=!#$%&'()*,:;<>?@[]^`{|}\"\\");
    }
}

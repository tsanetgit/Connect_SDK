package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SessionGuardTest {

    @TempDir
    Path dataDir;

    private EnvironmentService environments;
    private SessionGuard guard;

    @AfterEach
    void closeSessions() {
        environments.close();
    }

    @BeforeEach
    void setUp() {
        environments = new EnvironmentService(new DemoProperties(
            Map.of("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // the tests point at a local http mock that is never contacted
            null,
            null,  // credentials not locked
            null  // no seeds
        ));
        guard = new SessionGuard(environments);
    }

    /** A server that answers sign-in with {@code loginBody}, and every other call with {@code otherStatus}. */
    private final class TokenServer implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        volatile String loginBody;
        volatile int otherStatus = 200;
        private EnvironmentService local;

        TokenServer() throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String path = exchange.getRequestURI().getPath();
                String body = path.equals("/v1/login") ? loginBody
                    : path.equals("/v1/me")
                        ? "{\"company\":{\"id\":303,\"name\":\"Sender\"},\"user\":{\"id\":1,\"username\":\"u\",\"email\":\"u@example.test\"}}"
                        : "[]";
                int status = path.startsWith("/v1/login") || path.equals("/v1/me") ? 200 : otherStatus;
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        SessionGuard guard() {
            local = new EnvironmentService(new DemoProperties(
                Map.of("beta", new DemoProperties.EnvironmentDef("Beta",
                    "http://127.0.0.1:" + server.getAddress().getPort(), null, null)),
                "beta",
                dataDir.toString(),
                true,  // a local http server
                null,
                null,  // credentials not locked
                null  // no seeds
            ));
            local.credentialsFor("beta").save("u@example.test", "a-password");
            return new SessionGuard(local);
        }

        @Override
        public void close() {
            if (local != null) {
                local.close();
            }
            server.stop(0);
        }
    }

    @Test
    void aTokenThatCantBeAHeaderIsAReasonWithoutTheToken() throws Exception {
        try (TokenServer tokens = new TokenServer()) {
            tokens.loginBody = "{\"accessToken\":\"good\\r\\nMARKER\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
            SessionGuard guard = tokens.guard();

            assertThatThrownBy(guard::session)
                .isInstanceOfSatisfying(com.tsanet.api.ConnectApiException.class, e -> {
                    var answer = new ApiErrorHandler().handleConnectApi(e);
                    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(answer.getBody()).containsEntry("error", "Token request").containsEntry("detail",
                        "The token response holds an access token that can't be sent in a header");
                });
        }
    }

    /** The route from {@code tsanetgit/Connect_SDK#178}: sdkInput answers 400 with the message of what it catches. */
    @Test
    void aRenewedTokenThatCantBeAHeaderIsNotABadRequestQuotingIt() throws Exception {
        try (TokenServer tokens = new TokenServer()) {
            tokens.loginBody = "{\"accessToken\":\"good\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
            var session = tokens.guard().session();
            tokens.loginBody = "{\"accessToken\":\"good\\r\\nMARKER\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
            tokens.otherStatus = 401;

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> SessionGuard.sdkInput(() -> session.webhooks().listSubscriptions()));

            assertThat(thrown).isNotNull().isNotInstanceOf(ResponseStatusException.class);
            assertThat(thrown).isInstanceOfSatisfying(com.tsanet.api.ConnectApiException.class,
                e -> assertThat(new ApiErrorHandler().handleConnectApi(e).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
            for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContain("MARKER");
            }
        }
    }

    @Test
    void itReturns428WhenNoCredentialsAreStored() {
        assertThatThrownBy(guard::session)
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                assertThat(e.getReason()).contains("credentials not configured");
            });
    }

    @Test
    void itReturns428WhenStoredCredentialsAreUnusableForTheEnvironment() {
        // OAuth credentials stored for an environment with no Entra tenant:
        // loadable, but no usable auth config can be formed from them.
        environments.credentialsFor("beta").saveOAuth("client-id", "client-secret");
        assertThatThrownBy(guard::session)
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                assertThat(e.getReason()).contains("OAuth mode unavailable");
            });
    }

    @Test
    void aNamedEnvironmentUsesItsOwnCredentialsNotTheActiveOnes() {
        EnvironmentService two = new EnvironmentService(new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // never contacted: dev has no credentials, so no session is built
            null,
            null,  // credentials not locked
            null  // no seeds
        ));
        try {
            two.credentialsFor("beta").save("user@example.com", "pw");

            assertThatThrownBy(() -> new SessionGuard(two).session("dev"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(e.getReason()).startsWith("Dev credentials not configured");
                });
        } finally {
            two.close();
        }
    }
}

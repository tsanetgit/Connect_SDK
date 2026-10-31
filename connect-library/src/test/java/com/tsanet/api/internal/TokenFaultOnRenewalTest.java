package com.tsanet.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.ConnectApiException;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.api.connectapi.internal.ConnectApiRestTemplates;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.OAuthTokenGateway;
import com.tsanet.api.connectapi.internal.TokenManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Renewing an expired session: a token endpoint that refused is an expired session, a 401. A URL or an
 * answer the SDK can't use isn't, because logging in again wouldn't help. Real gateways and a real JDK
 * client, through the one place the bearer supplier classifies these.
 */
class TokenFaultOnRenewalTest {
    private HttpServer server;
    private String tokenUrl;
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private Throwable renewAgainst(String url) {
        ConnectApiSessionStore store = new ConnectApiSessionStore();
        store.saveOAuth("account", "stale", Instant.now().minusSeconds(3600));
        TokenManager manager = new TokenManager(store, mock(ConnectApiAuthGateway.class),
            new OAuthTokenGateway(ConnectApiRestTemplates.newHttpClient()), "account",
            new ClientCredentialsAuthConfig("tenant", url, "client-id", "client-secret", "api://audience", null));
        return catchThrowable(() -> TsaNetApiRuntime.bearerTokenForRequest(store, manager,
            mock(TsaNetApiConfiguration.class)));
    }

    private static void assertIsAFaultNotAnExpiredSession(Throwable thrown) {
        assertThat(thrown).isInstanceOfSatisfying(ConnectApiException.class, e -> {
            assertThat(e.status()).as("not a 401").isZero();
            assertThat(e.title()).isEqualTo(ConnectApiException.TOKEN_REQUEST);
            assertThat(e.kind()).isEqualTo(ConnectApiException.Kind.OTHER);
        });
        assertThat(thrown).hasNoCause();
        assertThat(thrown.toString()).doesNotContain("MARKER").doesNotContain("Session expired");
    }

    @Test
    void aTokenUrlSpringCantExpandIsNotAnExpiredSession() {
        assertIsAFaultNotAnExpiredSession(renewAgainst("http://login.example/{MARKER}/token"));
    }

    @Test
    void aTokenResponseThatIsNotJsonIsNotAnExpiredSession() {
        body = "{\"access_token\":MARKER}";
        assertIsAFaultNotAnExpiredSession(renewAgainst(tokenUrl));
    }

    @Test
    void aTokenResponseWithNoAccessTokenIsNotAnExpiredSession() {
        body = "{\"expires_in\":3600}";
        assertIsAFaultNotAnExpiredSession(renewAgainst(tokenUrl));
    }

    @Test
    void aTokenThatCantBeAHeaderIsNotAnExpiredSession() {
        body = "{\"access_token\":\"good\\r\\nMARKER\",\"expires_in\":3600}";
        assertIsAFaultNotAnExpiredSession(renewAgainst(tokenUrl));
    }

    @ParameterizedTest
    @CsvSource({"500,server_error", "503,temporarily_unavailable", "429,temporarily_unavailable",
        "408,temporarily_unavailable"})
    void aTokenEndpointFailingOrThrottlingIsNotAnExpiredSession(int failing, String code) {
        status = failing;
        body = "{\"error\":\"" + code + "\",\"error_description\":\"app MARKER\"}";
        Throwable thrown = renewAgainst(tokenUrl);
        assertIsAFaultNotAnExpiredSession(thrown);
        assertThat(((ConnectApiException) thrown).detail())
            .isEqualTo("OAuth client-credentials request failed: HTTP " + failing + " (" + code + ")");
    }

    @Test
    void aRefusalIsStillAnExpiredSession() {
        status = 401;
        body = "{\"error\":\"invalid_client\",\"error_description\":\"app MARKER\"}";

        assertThat(renewAgainst(tokenUrl)).isInstanceOfSatisfying(ConnectApiException.class, e -> {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.title()).isEqualTo("Session expired");
            assertThat(e.detail()).isEqualTo("OAuth client-credentials request failed: HTTP 401 (invalid_client)");
        });
    }
}

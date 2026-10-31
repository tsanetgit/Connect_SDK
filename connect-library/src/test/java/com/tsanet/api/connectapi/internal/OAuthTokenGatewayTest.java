package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuthTokenGatewayTest {
    @Test
    void itFetchesClientCredentialsToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(
                "{\"access_token\":\"token-123\",\"expires_in\":7200}",
                MediaType.APPLICATION_JSON
            ));

        OAuthTokenGateway gateway = new OAuthTokenGateway(restTemplate);
        OAuthAccessToken token = gateway.fetchClientCredentialsToken(
            new ClientCredentialsAuthConfig("tenant", null, "client-id", "client-secret", "api://audience", null)
        );

        assertThat(token.accessToken()).isEqualTo("token-123");
        assertThat(token.expiresInSeconds()).isEqualTo(7200);
        server.verify();
    }

    @Test
    void aRefusedTokenCallOnTheSharedClientNamesOnlyTheStatusAndCodeNotAConnectApiException() throws Exception {
        // The token call rides the session's JDK client through transport(), not create(): an
        // Entra refusal must not be classified by the Connect API's error handler, and nothing of
        // its body but the error code may reach the message: error_description names the app.
        com.sun.net.httpserver.HttpServer entra =
            com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.atomic.AtomicReference<String> userAgent = new java.util.concurrent.atomic.AtomicReference<>();
        entra.createContext("/", exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"error\":\"invalid_client\",\"error_description\":\"AADSTS7000215: app client-id-MARKER\","
                + "\"title\":\"t\",\"type\":\"x\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        entra.start();
        try {
            String tokenUrl = "http://127.0.0.1:" + entra.getAddress().getPort() + "/tenant/oauth2/v2.0/token";
            OAuthTokenGateway gateway = new OAuthTokenGateway(ConnectApiRestTemplates.newHttpClient());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> gateway.fetchClientCredentialsToken(
                new ClientCredentialsAuthConfig("tenant", tokenUrl, "client-id", "client-secret", "api://audience", null)));
            assertThat(thrown)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("OAuth client-credentials request failed: HTTP 401 (invalid_client)");
            // Not only the message: a logged stack trace prints every cause's message, and Spring's
            // response exception names the token URL (the tenant) and quotes Entra's body (the app).
            for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
                assertThat(String.valueOf(t.getMessage()))
                    .doesNotContain("MARKER").doesNotContain("/tenant/").doesNotContain("127.0.0.1");
            }
            // The JDK client sends "Java-http-client/<version>"; HttpURLConnection sends
            // "Java/<version>". Pins the token call to the session's transport (tsanetgit/Connect_SDK#131).
            assertThat(userAgent.get()).startsWith("Java-http-client/");
        } finally {
            entra.stop(0);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
        "unsupported_grant_type", "invalid_scope", "invalid_resource", "server_error",
        "temporarily_unavailable"
    })
    void aRefusalNamesAKnownErrorCodeAndNotItsDescription(String code) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":\"" + code + "\",\"error_description\":\"app MARKER\"}"));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> new OAuthTokenGateway(restTemplate)
            .fetchClientCredentialsToken(new ClientCredentialsAuthConfig("tenant", null, "client-id", "client-secret", "api://audience", null)));

        assertThat(thrown)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("OAuth client-credentials request failed: HTTP 400 (" + code + ")")
            .hasNoCause();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        // Well-formed JSON, and an error member that isn't one of the known codes.
        "{\"error\":\"marker_tenant\"}",
        "{\"error\":\"INVALID_CLIENT\"}",
        "{\"error\":\"invalid_client MARKER\"}",
        "{\"error\":\"invalid_client\\nMARKER\"}",
        "{\"error\":{\"code\":\"invalid_client\"}}",
        "{\"error\":[\"invalid_client\"]}",
        "{\"error\":1}",
        "{\"error\":null}",
        "{\"error_description\":\"MARKER\"}",
        "[\"invalid_client\"]",
        // Not JSON at all, and an empty body.
        "<html>MARKER invalid_client</html>",
        "",
        " \n\t "
    })
    void aRefusalWithoutAKnownErrorCodeKeepsTheStatusOnlyMessage(String body) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                .withStatus(org.springframework.http.HttpStatus.BAD_REQUEST).body(body));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> new OAuthTokenGateway(restTemplate)
            .fetchClientCredentialsToken(new ClientCredentialsAuthConfig("tenant", null, "client-id", "client-secret", "api://audience", null)));

        assertThat(thrown)
            .isExactlyInstanceOf(IllegalStateException.class)
            .hasMessage("OAuth client-credentials request failed: HTTP 400")
            .hasNoCause();
    }

    @Test
    void anUnreachableTokenEndpointIsConnectivityNamingNeitherTheUrlNorTheTenant() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        String tokenUrl = "http://127.0.0.1:" + closedPort + "/TENANT-MARKER/oauth2/v2.0/token";
        OAuthTokenGateway gateway = new OAuthTokenGateway(ConnectApiRestTemplates.newHttpClient());

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> gateway.fetchClientCredentialsToken(
            new ClientCredentialsAuthConfig("TENANT-MARKER", tokenUrl, "client-id", "client-secret", "api://audience", null)));

        assertThat(thrown).isInstanceOfSatisfying(com.tsanet.api.ConnectApiException.class, ex -> {
            assertThat(ex.kind()).isEqualTo(com.tsanet.api.ConnectApiException.Kind.CONNECTIVITY);
            assertThat(ex.title()).isEqualTo(com.tsanet.api.ConnectApiException.TOKEN_ENDPOINT);
            assertThat(ex.getMessage()).isEqualTo("cannot reach the OAuth token endpoint (" + ex.detail() + ")");
        });
        // Not only the message: nothing in the cause chain may carry the URL either, since a
        // logged stack trace prints every cause's message.
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            assertThat(String.valueOf(t.getMessage()))
                .doesNotContain("TENANT-MARKER").doesNotContain("127.0.0.1").doesNotContain(String.valueOf(closedPort));
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 30, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aTokenCallOnAnInterruptedThreadIsOtherAndTheFlagStaysSet() throws Exception {
        // HttpURLConnection ignores interrupts, so on that transport this call would wait on the
        // silent listener forever; the timeout, on its own thread, turns that regression into a failure.
        Throwable thrown;
        boolean stillInterrupted;
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            String tokenUrl = "http://127.0.0.1:" + silent.getLocalPort() + "/TENANT-MARKER/oauth2/v2.0/token";
            OAuthTokenGateway gateway = new OAuthTokenGateway(ConnectApiRestTemplates.newHttpClient());

            Thread.currentThread().interrupt();
            try {
                thrown = org.assertj.core.api.Assertions.catchThrowable(() -> gateway.fetchClientCredentialsToken(
                    new ClientCredentialsAuthConfig("TENANT-MARKER", tokenUrl, "client-id", "client-secret", "api://audience", null)));
            } finally {
                stillInterrupted = Thread.interrupted();
            }
        }

        assertThat(stillInterrupted).isTrue();
        assertThat(thrown).isInstanceOfSatisfying(com.tsanet.api.ConnectApiException.class, ex -> {
            assertThat(ex.kind()).isEqualTo(com.tsanet.api.ConnectApiException.Kind.OTHER);
            assertThat(ex.detail()).isEqualTo("interrupted");
            assertThat(ex.getCause()).isInstanceOf(InterruptedException.class);
        });
    }
}

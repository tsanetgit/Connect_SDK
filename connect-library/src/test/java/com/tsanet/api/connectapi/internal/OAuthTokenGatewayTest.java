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
}

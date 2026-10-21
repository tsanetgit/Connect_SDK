package com.tsanet.api.connectapi.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tsanet.api.ConnectApiException;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

public class OAuthTokenGateway {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final RestTemplate restTemplate;

    /**
     * The token call rides the session's JDK client but none of the Connect API's error handling:
     * a refusal stays the status-only {@code IllegalStateException} below, and a transport failure
     * is {@code ConnectApiException} with no URL or tenant (tsanetgit/Connect_SDK#131).
     */
    public OAuthTokenGateway(HttpClient httpClient) {
        this(new RestTemplate(ConnectApiRestTemplates.transport(httpClient)));
    }

    OAuthTokenGateway(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public OAuthAccessToken fetchClientCredentialsToken(ClientCredentialsAuthConfig config) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("scope", config.resolvedScope());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                config.resolvedTokenUrl(),
                new HttpEntity<>(form, headers),
                String.class
            );
            return parseTokenResponse(response.getBody());
        } catch (RestClientResponseException ex) {
            // No cause: Spring's message names the token URL, which carries the tenant, and quotes
            // Entra's error body, which names the app (tsanetgit/Connect_SDK#131).
            throw new IllegalStateException(
                "OAuth client-credentials request failed: HTTP " + ex.getStatusCode().value()
            );
        } catch (ResourceAccessException ex) {
            // Spring's message embeds the token URL, and the URL carries the tenant: keep only the
            // transport's own exception (tsanetgit/Connect_SDK#131). Spring's JDK request reports an
            // interrupt as an IOException caused by the InterruptedException, with the flag set again.
            // RestTemplate always wraps the transport's IOException; never fall back to the wrapper.
            Throwable transport = ex.getCause() == null ? new java.io.IOException("no transport cause") : ex.getCause();
            if (transport.getCause() instanceof InterruptedException interrupted) {
                throw ConnectApiException.interrupted(interrupted);
            }
            throw ConnectApiException.tokenEndpointUnreachable(transport);
        }
    }

    private static OAuthAccessToken parseTokenResponse(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            String accessToken = textValue(root, "access_token");
            long expiresIn = root.path("expires_in").asLong(3600);
            return new OAuthAccessToken(accessToken, expiresIn);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse OAuth token response", ex);
        }
    }

    private static String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException("OAuth token response missing " + field);
        }
        return node.asText();
    }
}

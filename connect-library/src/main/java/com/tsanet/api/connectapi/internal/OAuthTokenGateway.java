package com.tsanet.api.connectapi.internal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import java.util.List;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

public class OAuthTokenGateway {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    public OAuthTokenGateway() {
        this(new RestTemplate());
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
            throw new IllegalStateException(
                "OAuth client-credentials request failed: HTTP " + ex.getStatusCode().value(),
                ex
            );
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

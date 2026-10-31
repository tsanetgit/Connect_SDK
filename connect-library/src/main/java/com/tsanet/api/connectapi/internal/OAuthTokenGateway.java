package com.tsanet.api.connectapi.internal;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.tsanet.api.ConnectApiException;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.OAuthAccessToken;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;
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
    // RFC 6749 §5.2, its two server-side codes of §4.1.2.1, and invalid_resource, which Entra's
    // error code reference lists for a resource not found or not configured in the tenant. A closed
    // list, not a shape: a proxy's error member could hold any lowercase word, and only these are
    // known to name nothing.
    private static final Set<String> OAUTH_ERROR_CODES = Set.of(
        "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
        "unsupported_grant_type", "invalid_scope", "invalid_resource", "server_error",
        "temporarily_unavailable"
    );

    private final RestTemplate restTemplate;

    /**
     * The token call rides the session's JDK client but none of the Connect API's error handling:
     * a refusal stays the {@code IllegalStateException} below, with the status and at most OAuth's
     * error code; a URL or answer it can't use is {@link ConnectApiException#tokenRequestFailed}; and
     * a transport failure is {@code ConnectApiException} with no URL or tenant
     * (tsanetgit/Connect_SDK#131).
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
        } catch (IllegalArgumentException ex) {
            // Spring's message can quote the URL it couldn't expand, and the URL carries the tenant. The
            // JDK client throws this too on a response it can't read, so the message covers both.
            throw ConnectApiException.tokenRequestFailed("The OAuth token request failed: its URL or the response isn't usable");
        } catch (RestClientResponseException ex) {
            // No cause: Spring's message names the token URL, which carries the tenant, and quotes
            // Entra's error body, which names the app (tsanetgit/Connect_SDK#131). That is also why
            // only the error code is read from the body, never error_description.
            String failed = "OAuth client-credentials request failed: HTTP " + ex.getStatusCode().value()
                + oauthErrorCodeSuffix(ex.getResponseBodyAsString());
            // A 5xx, a request timeout or throttling is the endpoint failing, not a refusal of these credentials.
            int status = ex.getStatusCode().value();
            if (ex.getStatusCode().is5xxServerError() || status == 408 || status == 429) {
                throw ConnectApiException.tokenRequestFailed(failed);
            }
            throw new IllegalStateException(failed);
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

    private static String oauthErrorCodeSuffix(String body) {
        JsonNode error;
        try {
            error = OBJECT_MAPPER.readTree(body).get("error");
        } catch (JacksonException notJson) {
            return "";
        }
        if (error == null || !error.isString() || !OAUTH_ERROR_CODES.contains(error.stringValue())) {
            return "";
        }
        return " (" + error.stringValue() + ")";
    }

    private static OAuthAccessToken parseTokenResponse(String body) {
        OAuthAccessToken token;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            String accessToken = textValue(root, "access_token");
            long expiresIn = root.path("expires_in").asLong(3600);
            token = new OAuthAccessToken(accessToken, expiresIn);
        } catch (IllegalStateException missing) {
            // textValue's own message, which names the field and no value.
            throw ConnectApiException.tokenRequestFailed(missing.getMessage());
        } catch (Exception ex) {
            // No cause: Jackson's message quotes a token it doesn't recognize, which can be this one.
            throw ConnectApiException.tokenRequestFailed("Failed to parse OAuth token response");
        }
        BearerTokens.requireSendable(token.accessToken());
        return token;
    }

    private static String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException("OAuth token response missing " + field);
        }
        return node.asText();
    }
}

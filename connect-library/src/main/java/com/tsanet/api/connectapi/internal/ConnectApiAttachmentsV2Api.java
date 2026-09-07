package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.generated.invoker.ApiClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AttachmentsV2Api} over the generated {@link ApiClient}'s raw {@code invokeAPI}, with
 * the {@code bearerAuth} scheme, so the session's bearer supplier and the 401 re-auth
 * interceptor apply exactly as they do to the generated V1 calls. The V2 paths are not in
 * the OpenAPI spec yet (tsanetgit/Connect-API-Code#147 is a draft), which is the only
 * reason this class exists instead of a generated one.
 *
 * <p>Errors: an {@code application/problem+json} body is mapped to
 * {@link AttachmentV2Exception} with the platform's {@code type}, {@code title} and
 * {@code detail}; nothing from the request is echoed. A response that is not a problem
 * document yields the status alone.
 */
public final class ConnectApiAttachmentsV2Api implements AttachmentsV2Api {

    static final String GRANTS_PATH = "/v2/collaboration-requests/{token}/attachments/grants";
    static final String GRANT_PATH = GRANTS_PATH + "/{grantId}";
    static final String COMPLETE_PATH = GRANT_PATH + "/complete";
    private static final String[] AUTH_NAMES = {"bearerAuth"};
    private static final String[] ACCEPT = {"application/json", "application/problem+json"};
    private static final String[] JSON = {"application/json"};

    private final ApiClient apiClient;
    private final JsonMapper problemMapper = JsonMapper.builder().build();

    public ConnectApiAttachmentsV2Api(ApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public AttachmentGrant createGrant(String caseToken, AttachmentGrantRequest request, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        ResponseEntity<AttachmentGrant> response = invoke("grant", GRANTS_PATH, HttpMethod.POST,
            Map.of("token", caseToken), request, headers, new ParameterizedTypeReference<AttachmentGrant>() { });
        AttachmentGrant grant = response.getBody();
        if (grant == null || grant.grantId() == null || grant.upload() == null) {
            throw new AttachmentV2Exception("grant returned no usable grant (missing grantId or upload block)",
                response.getStatusCode().value(), AttachmentV2Exception.CLIENT_PRECONDITION);
        }
        return grant;
    }

    @Override
    public AttachmentCompleteResult complete(String caseToken, UUID grantId, AttachmentCompleteRequest request) {
        ResponseEntity<AttachmentCompleteResult> response = invoke("complete", COMPLETE_PATH, HttpMethod.POST,
            Map.of("token", caseToken, "grantId", grantId.toString()),
            request, new HttpHeaders(), new ParameterizedTypeReference<AttachmentCompleteResult>() { });
        AttachmentCompleteResult result = response.getBody();
        if (result == null || result.status() == null) {
            throw new AttachmentV2Exception("complete returned no outcome status", response.getStatusCode().value(),
                AttachmentV2Exception.CLIENT_PRECONDITION);
        }
        return result;
    }

    @Override
    public void abandon(String caseToken, UUID grantId) {
        invoke("abandon", GRANT_PATH, HttpMethod.DELETE, Map.of("token", caseToken, "grantId", grantId.toString()),
            null, new HttpHeaders(), new ParameterizedTypeReference<Void>() { });
    }

    private <T> ResponseEntity<T> invoke(String operation, String path, HttpMethod method, Map<String, Object> pathParams,
                                         Object body, HttpHeaders headers, ParameterizedTypeReference<T> returnType) {
        List<MediaType> accept = apiClient.selectHeaderAccept(ACCEPT);
        MediaType contentType = body == null ? null : apiClient.selectHeaderContentType(JSON);
        try {
            return apiClient.invokeAPI(path, method, pathParams, new LinkedMultiValueMap<>(),
                body, headers, new LinkedMultiValueMap<>(), new LinkedMultiValueMap<>(), accept, contentType,
                AUTH_NAMES, returnType);
        } catch (HttpStatusCodeException e) {
            throw translate(operation, e);
        } catch (ResourceAccessException e) {
            // Spring's message carries the expanded request URL, and these paths carry the
            // case token: name the failure by its cause type only.
            Throwable root = e.getCause() != null ? e.getCause() : e;
            throw new AttachmentV2Exception(operation + " failed: cannot reach the Connect API ("
                + root.getClass().getSimpleName() + ")", 0, AttachmentV2Exception.CONNECTIVITY, e);
        } catch (RestClientException e) {
            throw new AttachmentV2Exception(operation + " failed: " + e.getClass().getSimpleName(),
                0, AttachmentV2Exception.CONNECTIVITY, e);
        }
    }

    /** Problem details in, value-free exception out. */
    private AttachmentV2Exception translate(String operation, HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        String type = null;
        String title = null;
        String detail = null;
        String raw = e.getResponseBodyAsString();
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = problemMapper.readTree(raw);
                type = text(node, "type");
                title = text(node, "title");
                detail = text(node, "detail");
            } catch (RuntimeException ignored) {
                // Not a problem document; the status alone is the message.
            }
        }
        StringBuilder message = new StringBuilder(operation).append(" failed: HTTP ").append(status);
        if (title != null) {
            message.append(' ').append(title);
        }
        if (type != null) {
            message.append(" (").append(type).append(')');
        }
        if (detail != null) {
            message.append(": ").append(detail);
        }
        return new AttachmentV2Exception(message.toString(), status, type, e);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}

package com.tsanet.api.connectapi.internal;

import com.tsanet.api.ConnectApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns every non-2xx answer into a {@link ConnectApiException}, classified by the body
 * alone (tsanetgit/Connect_SDK#20): RFC 7807 fields mean {@code PROBLEM}; a {@code message}
 * field means the API's legacy shape, even on a 500 (tsanetgit/Connect-API-Code#122); anything
 * else is the status plus a short excerpt. The URL and method Spring passes in are never
 * used in the message.
 */
public final class ConnectApiResponseErrorHandler implements ResponseErrorHandler {

    static final int EXCERPT_LENGTH = 200;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body;
        try (InputStream in = response.getBody()) {
            body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            body = "";
        }
        throw classify(status, scrub(body, url));
    }

    /**
     * An intermediary's error page (a proxy 404, a gateway 502) commonly echoes the request
     * URI, and the URI carries the case token. Remove the URL and its path before the body can
     * become an excerpt.
     */
    static String scrub(String body, URI url) {
        if (body == null || url == null) {
            return body;
        }
        String scrubbed = body;
        if (url.toString() != null && !url.toString().isBlank()) {
            scrubbed = scrubbed.replace(url.toString(), "<request-url>");
        }
        if (url.getRawPath() != null && !url.getRawPath().isBlank()) {
            scrubbed = scrubbed.replace(url.getRawPath(), "<request-path>");
        }
        if (url.getPath() != null && !url.getPath().isBlank()) {
            scrubbed = scrubbed.replace(url.getPath(), "<request-path>");
        }
        return scrubbed;
    }

    /** Package-visible so the classification is testable without a transport. */
    ConnectApiException classify(int status, String body) {
        if (body != null && !body.isBlank()) {
            JsonNode node = null;
            try {
                node = mapper.readTree(body);
            } catch (RuntimeException ignored) {
                // Not JSON; fall through to the excerpt.
            }
            if (node != null && node.isObject()) {
                String type = text(node, "type");
                String title = text(node, "title");
                String detail = text(node, "detail");
                if (type != null || title != null || detail != null) {
                    int bodyStatus = node.has("status") && node.get("status").isInt() ? node.get("status").asInt() : status;
                    return new ConnectApiException(ConnectApiException.Kind.PROBLEM, bodyStatus, type, title, detail,
                        text(node, "instance"), null);
                }
                String message = text(node, "message");
                if (message != null) {
                    return new ConnectApiException(ConnectApiException.Kind.LEGACY, status, null, null,
                        withValidationErrors(message, node), null, null);
                }
            }
        }
        return new ConnectApiException(ConnectApiException.Kind.OTHER, status, null, null, excerpt(body), null, null);
    }

    private static String withValidationErrors(String message, JsonNode node) {
        JsonNode errors = node.get("validationErrors");
        if (errors == null || !errors.isArray() || errors.isEmpty()) {
            return message;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode error : errors) {
            String field = text(error, "field");
            String text = text(error, "message");
            if (text == null) {
                text = text(error, "defaultMessage");
            }
            if (text != null) {
                items.add(field == null ? text : field + ": " + text);
            }
        }
        return items.isEmpty() ? message : message + " [" + String.join("; ", items) + "]";
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String collapsed = body.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= EXCERPT_LENGTH ? collapsed : collapsed.substring(0, EXCERPT_LENGTH) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}

package com.tsanet.demo.web;

import com.tsanet.api.ConnectApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Maps SDK/transport failures to JSON the frontend can display, instead of
 * opaque 500s. Upstream Connect API errors keep their status code where the
 * generated client surfaces one.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("error", e.getReason() != null ? e.getReason() : e.getMessage()));
    }

    /**
     * The SDK's classified answer: the upstream status passes through (502 when the API could
     * not be reached), and the page gets the API's own title and detail plus the problem type.
     */
    @ExceptionHandler(ConnectApiException.class)
    public ResponseEntity<Map<String, String>> handleConnectApi(ConnectApiException e) {
        int status = e.kind() == ConnectApiException.Kind.CONNECTIVITY || e.status() < 400 ? 502 : e.status();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", e.title() != null ? e.title() : e.getMessage());
        if (e.detail() != null) {
            body.put("detail", e.detail());
        }
        if (e.type() != null) {
            body.put("type", e.type());
        }
        body.put("kind", e.kind().name());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(RestClientResponseException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of(
                "error", "Connect API returned " + e.getStatusCode().value(),
                "detail", e.getResponseBodyAsString()
            ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
    }
}

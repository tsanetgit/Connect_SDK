package com.tsanet.demo.web;

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

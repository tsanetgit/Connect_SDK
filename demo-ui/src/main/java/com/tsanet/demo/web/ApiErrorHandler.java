package com.tsanet.demo.web;

import com.tsanet.api.ConnectApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps SDK/transport failures to JSON the frontend can display, instead of
 * opaque 500s. Upstream Connect API errors keep their status code where the
 * generated client surfaces one. A request Spring rejects before any controller
 * code runs keeps the status {@link ResponseEntityExceptionHandler} gives it, so
 * it doesn't read as a Connect API failure.
 */
@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    private static final int MAX_CAUSES = 10;
    private static final String UNEXPECTED = "The demo hit an unexpected failure; the server log says where";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
            .body(Map.of("error", e.getReason() != null ? e.getReason() : e.getMessage()));
    }

    /**
     * The SDK's classified answer: the status is {@link #upstreamStatus} (the upstream error status
     * passes through; 502 when the API could not be reached or the status isn't one), and the page
     * gets the API's own title and detail plus the problem type.
     */
    @ExceptionHandler(ConnectApiException.class)
    public ResponseEntity<Map<String, String>> handleConnectApi(ConnectApiException e) {
        HttpStatusCode status = upstreamStatus(e.kind() == ConnectApiException.Kind.CONNECTIVITY, e.status());
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

    /**
     * The status demo-ui answers for a Connect API failure the SDK has classified (a
     * {@link ConnectApiException}, or a V2 {@code AttachmentV2Exception}): the platform's own status
     * passes through, and a call that couldn't reach it, or failed without a platform error status
     * (anything outside 400 to 599), is a 502. An unclassified {@link RestClientResponseException}
     * keeps its own 502 below.
     */
    static HttpStatusCode upstreamStatus(boolean unreachable, int status) {
        return unreachable || status < 400 || status > 599 ? HttpStatus.BAD_GATEWAY : HttpStatusCode.valueOf(status);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(RestClientResponseException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(Map.of(
                "error", "Connect API returned " + e.getStatusCode().value(),
                "detail", e.getResponseBodyAsString()
            ));
    }

    /** Every request Spring rejects itself: its status, headers and text, in the page's shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> answer = super.handleExceptionInternal(e, body, headers, status, request);
        if (answer == null) {
            return null; // the response is already committed
        }
        String detail = answer.getBody() instanceof ProblemDetail problem ? problem.getDetail() : null;
        return ResponseEntity.status(answer.getStatusCode()).headers(answer.getHeaders())
            .body(Map.of("error", detail != null ? detail : "HTTP " + status.value()));
    }

    /** A fixed message: the parser's text quotes the body, and a missing body's names the demo's own method. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail fixed = ProblemDetail.forStatusAndDetail(status, "The request body is missing or does not match the expected JSON");
        return handleExceptionInternal(e, fixed, headers, status, request);
    }

    /**
     * Keeps the 502 this has always answered, with a fixed message. A message the page should show is
     * translated where the call is made ({@link SessionGuard#sdkInput}, and the sign-in in
     * {@link SessionGuard#session(String)}). The log gets the exception types and where it failed, not
     * the messages, which can quote a value: this repo's CLAUDE.md keeps secrets out of logs.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        logger.error("Unexpected failure: " + describe(e));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", UNEXPECTED));
    }

    /** Each type in the cause chain, then the first frame in this codebase, or the top frame without one. */
    private static String describe(Throwable e) {
        StringBuilder chain = new StringBuilder();
        Throwable t = e;
        for (int depth = 0; t != null && depth < MAX_CAUSES; depth++, t = t.getCause()) { // a chain can be a cycle
            chain.append(chain.isEmpty() ? "" : " caused by ").append(t.getClass().getName());
        }
        StackTraceElement[] stack = e.getStackTrace();
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().startsWith("com.tsanet.")) {
                return chain + " at " + frame;
            }
        }
        return stack.length > 0 ? chain + " at " + stack[0] : chain.toString();
    }
}

package com.tsanet.api.connectapi.internal;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import java.util.function.Supplier;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * One copy of how a V2 attachment call's failure becomes an {@link AttachmentV2Exception}, for
 * the gateways over the generated V2 APIs ({@link ConnectApiAttachmentsV2Gateway} and
 * {@link ConnectApiAttachmentStorageGateway}). Every message is value-free: it names the
 * operation and the status, never a request URL, which can carry a case token.
 *
 * <p>An answer that arrives without a field the spec requires is {@code client/precondition},
 * named by the field and never by its value.
 *
 * <p>The code follows the HTTP status. A gateway that reads a status as an outcome rather than
 * a failure (the storage config's {@code 404} means "none registered") catches the code after
 * this mapping; nothing here special-cases one endpoint.
 */
final class ConnectApiErrors {

    /** The library's own problem-body parser, used here only to read a problem's type. */
    private static final ConnectApiResponseErrorHandler PROBLEM_BODIES = new ConnectApiResponseErrorHandler();

    private ConnectApiErrors() {
    }

    /**
     * Whether the answer behind {@code cause} names the problem type {@code typeSuffix}, matched as
     * {@link ConnectApiException#isProblem} matches it. One decision for both error shapes
     * {@link #call} maps: the library's {@link ConnectApiException}, and a plain RestTemplate's
     * {@link HttpStatusCodeException}. For the second, the unscrubbed body goes through the
     * library's own parser (which logs nothing) for its type alone, and nothing from it reaches a
     * message. The exception itself stays attached as the cause, as {@link #call} already
     * attaches it.
     */
    static boolean isProblem(Throwable cause, String typeSuffix) {
        if (cause instanceof ConnectApiException e) {
            return e.isProblem(typeSuffix);
        }
        if (cause instanceof HttpStatusCodeException e) {
            return PROBLEM_BODIES.classify(e.getStatusCode().value(), e.getResponseBodyAsString()).isProblem(typeSuffix);
        }
        return false;
    }

    static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (ConnectApiException e) {
            throw translate(operation, e);
        } catch (HttpStatusCodeException e) {
            // Only a RestTemplate without the library's error handler answers this way. Its body
            // is unscrubbed and can echo the request path, which can carry the case token: keep
            // the status, not the words.
            int status = e.getStatusCode().value();
            throw new AttachmentV2Exception(operation + " failed: HTTP " + status, status, codeFor(status), e);
        } catch (ResourceAccessException e) {
            // Spring's message can carry the expanded request URL, and some of these paths carry
            // the case token: name the failure by its type only.
            throw new AttachmentV2Exception(operation + " failed: " + e.getClass().getSimpleName(), 0,
                AttachmentV2Exception.CONNECTIVITY, e);
        } catch (RestClientException e) {
            // An answer arrived and could not be read, for example a value the generated model
            // doesn't know. Not a connectivity failure, so not retried.
            throw new AttachmentV2Exception(operation + " failed: could not read the answer ("
                + e.getClass().getSimpleName() + ")", 0, AttachmentV2Exception.API_ERROR, e);
        }
    }

    /** The runtime's client already classified the answer; keep its words and map its status to a code. */
    static AttachmentV2Exception translate(String operation, ConnectApiException e) {
        if (e.kind() == ConnectApiException.Kind.CONNECTIVITY) {
            return new AttachmentV2Exception(operation + " failed: " + e.getMessage(), 0,
                AttachmentV2Exception.CONNECTIVITY, e);
        }
        return new AttachmentV2Exception(operation + " failed: " + e.getMessage(), e.status(), codeFor(e.status()), e);
    }

    /** A field the spec requires; its absence is named, never its value. */
    static <T> T required(T value, String field) {
        if (value == null) {
            throw new AttachmentV2Exception("the answer has no " + field, 0, AttachmentV2Exception.CLIENT_PRECONDITION);
        }
        return value;
    }

    /** A 2xx with no body where the spec promises one. */
    static AttachmentV2Exception emptyAnswer(String operation) {
        return new AttachmentV2Exception(operation + " returned no usable answer", 0,
            AttachmentV2Exception.CLIENT_PRECONDITION);
    }

    /** The code for an error status, as the spec documents each one for the V2 attachment endpoints. */
    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> AttachmentV2Exception.INVALID_REQUEST;
            case 403 -> AttachmentV2Exception.FORBIDDEN;
            case 404 -> AttachmentV2Exception.NOT_FOUND;
            case 409 -> AttachmentV2Exception.GRANT_TERMINAL;
            case 422 -> AttachmentV2Exception.UPLOAD_MISMATCH;
            case 502 -> AttachmentV2Exception.PROVIDER_ERROR;
            default -> AttachmentV2Exception.API_ERROR;
        };
    }
}

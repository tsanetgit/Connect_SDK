package com.tsanet.api.connectapi.internal;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import java.time.Duration;
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

    private ConnectApiErrors() {
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

    /**
     * The one interrupt rule for the V2 upload and complete calls: an interrupted thread sends
     * nothing more. Called before every outbound call and every retry wait, whatever the wait's
     * length. The interrupt stays set; {@code trigger}, the failure a retry was answering, if any,
     * is kept as suppressed.
     */
    static void requireNotInterrupted(String what, Throwable trigger) {
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted(what, null, trigger);
        }
    }

    /** Waits before a retry; an interrupt before or during the wait stops the call, as {@link #requireNotInterrupted} does. */
    static void pause(Duration duration, String what, Throwable trigger) {
        requireNotInterrupted(what, trigger);
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted(what, e, trigger);
        }
    }

    /** {@code client/interrupted}, with the interrupt as cause and {@code trigger}, if any, as suppressed. */
    static AttachmentV2Exception interrupted(String what, InterruptedException cause, Throwable trigger) {
        AttachmentV2Exception e = new AttachmentV2Exception("interrupted " + what, 0, AttachmentV2Exception.INTERRUPTED,
            cause);
        if (trigger != null) {
            e.addSuppressed(trigger);
        }
        return e;
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

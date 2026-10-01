package com.tsanet.api.attachments.v2;

/**
 * Any failure on the V2 attachment path: an error answer from the Connect API, a rejected or
 * failed upload request, or a client-side precondition. The message is value-free by
 * construction: it carries the operation, the HTTP status and the platform's own title and
 * detail, never a signed URL, a header value or a token.
 *
 * <p>The cause, kept for diagnosis, is the underlying Connect API or I/O exception. A Spring
 * client exception there can name the request URL, which carries the case token; it never
 * carries an upload link, which this client keeps out of every exception it builds.
 *
 * <p>{@link #code()} says what happened. The V2 endpoints define no problem types, so the
 * {@code attachment/...} codes come from the HTTP status of the call that failed, as the spec
 * documents each one; the {@code client/...} codes are failures that never reached the platform.
 */
public class AttachmentV2Exception extends RuntimeException {

    /** 400: a link call named a part or block number outside the plan, or S3 receipts don't cover it. */
    public static final String INVALID_REQUEST = "attachment/invalid-request";
    /** 403: the caller's company isn't the case's sender, or the receiver isn't on its allowlist. */
    public static final String FORBIDDEN = "attachment/forbidden";
    /** 404: no such case or grant, or a link or complete call that doesn't match the grant's mode. */
    public static final String NOT_FOUND = "attachment/not-found";
    /** 409: the grant is completed, abandoned or expired, so it can't take this call. */
    public static final String GRANT_TERMINAL = "attachment/grant-terminal";
    /** 422: complete found the upload doesn't match the grant; the grant stays open. */
    public static final String UPLOAD_MISMATCH = "attachment/upload-mismatch";
    /** 502: the receiver's storage provider failed; nothing changed, retry later. */
    public static final String PROVIDER_ERROR = "attachment/provider-error";
    /** Any other non-2xx answer from the Connect API. */
    public static final String API_ERROR = "attachment/api-error";

    /** The storage answered an upload {@code PUT} with a status this client doesn't retry, or kept failing. */
    public static final String UPLOAD_REJECTED = "client/upload-rejected";
    /** An upload {@code PUT} could not reach the storage after the retry budget. */
    public static final String UPLOAD_UNREACHABLE = "client/upload-unreachable";
    /** A link is past its expiry and asking again returned the same link, so the upload can't go on. */
    public static final String LINK_NOT_REFRESHABLE = "client/link-not-refreshable";
    /** The grant's mode is not one this client uploads. */
    public static final String UNSUPPORTED_UPLOAD_MODE = "client/unsupported-upload-mode";
    /** A client-side precondition failed: the file, the plan, or a link that doesn't fit the plan. */
    public static final String CLIENT_PRECONDITION = "client/precondition";
    /** The Connect API could not be reached at all. */
    public static final String CONNECTIVITY = "client/connectivity";

    private final int status;
    private final String code;

    public AttachmentV2Exception(String message, int status, String code) {
        this(message, status, code, null);
    }

    public AttachmentV2Exception(String message, int status, String code, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** HTTP status of the failing response, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** One of this class's {@code attachment/...} or {@code client/...} constants. */
    public String code() {
        return code;
    }

    /** Whether {@link #code()} is {@code code}. */
    public boolean is(String code) {
        return this.code != null && this.code.equals(code);
    }
}

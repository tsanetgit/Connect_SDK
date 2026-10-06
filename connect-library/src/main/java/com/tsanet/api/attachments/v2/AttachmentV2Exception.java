package com.tsanet.api.attachments.v2;

/**
 * Any failure on the V2 attachment path, or in a receiver's storage setup
 * ({@link com.tsanet.api.facade.AttachmentStorageFacade}): an error answer from the Connect API, a
 * rejected or failed upload request, or a client-side precondition. The message is value-free by
 * construction: it carries the operation, the HTTP status and the platform's own title and
 * detail, never a signed URL, a header value or a token.
 *
 * <p>The cause, kept for diagnosis, is the underlying Connect API or I/O exception. A Spring
 * client exception there can name the request URL, which carries the case token; it never
 * carries an upload link, which this client keeps out of every exception it builds.
 *
 * <p>{@link #code()} says what happened. The {@code attachment/...} codes come from the HTTP
 * status of the call that failed, as the spec documents each one, with one exception:
 * {@link #RECEIVER_NOT_ALLOWED} is either the server's allowlist refusal on grant creation (a
 * {@code 403} with its own problem type) or this client's receiver allowlist refusing before any
 * grant request, where {@link #status()} is 0. The {@code client/...} codes are failures that
 * never reached the platform.
 */
public class AttachmentV2Exception extends RuntimeException {

    /**
     * 400: a link call named a part or block number outside the plan, or S3 receipts don't cover it;
     * or a storage configuration the platform won't register, including a method it hasn't enabled.
     */
    public static final String INVALID_REQUEST = "attachment/invalid-request";
    /** 403: the caller's company isn't the case's sender, or any other refusal not named below. */
    public static final String FORBIDDEN = "attachment/forbidden";
    /**
     * The case's receiving company isn't allowed: either this account's receiver allowlist
     * refused it before any grant request, or the server's sender allowlist refused grant
     * creation with a {@code 403}. Either way no grant exists and nothing was uploaded.
     */
    public static final String RECEIVER_NOT_ALLOWED = "attachment/receiver-not-allowed";
    /**
     * 404: no such case or grant, a receiver that has registered no storage configuration (on
     * create, or on a storage test), or a link or complete call that doesn't match the grant's mode.
     */
    public static final String NOT_FOUND = "attachment/not-found";
    /** 409: the grant is completed, abandoned or expired, so it can't take this call. */
    public static final String GRANT_TERMINAL = "attachment/grant-terminal";
    /** 422: complete found the upload doesn't match the grant. The platform leaves the grant open; {@code send} abandons it. */
    public static final String UPLOAD_MISMATCH = "attachment/upload-mismatch";
    /** 502: the receiver's storage provider failed; nothing changed, retry later. */
    public static final String PROVIDER_ERROR = "attachment/provider-error";
    /** Any other non-2xx answer from the Connect API, or an answer this client could not read. */
    public static final String API_ERROR = "attachment/api-error";

    /** The storage answered an upload {@code PUT} with a status this client doesn't retry, or kept failing. */
    public static final String UPLOAD_REJECTED = "client/upload-rejected";
    /** An upload {@code PUT} could not reach the storage after the retry budget. */
    public static final String UPLOAD_UNREACHABLE = "client/upload-unreachable";
    /** A link is past its expiry and asking again returned the same link, so the upload can't go on. */
    public static final String LINK_NOT_REFRESHABLE = "client/link-not-refreshable";
    // PROVISIONAL(tsanetgit/Connect-API-Code#182): gcsResumable is named because it is the one mode
    // this client refuses. Once GCS is on, the client uploads it and this javadoc names only a
    // missing mode.
    /** The grant has no mode, or one this client doesn't upload ({@code gcsResumable}). */
    public static final String UNSUPPORTED_UPLOAD_MODE = "client/unsupported-upload-mode";
    /**
     * A client-side precondition failed: the file is empty, unreadable or not the size the grant
     * expects; the grant's plan is missing or doesn't fit the file; a link doesn't fit the plan or
     * isn't a usable request; or an answer is empty or missing a field this client needs.
     */
    public static final String CLIENT_PRECONDITION = "client/precondition";
    /** The Connect API could not be reached, or its answer was lost. */
    public static final String CONNECTIVITY = "client/connectivity";
    /** The calling thread was interrupted; the interrupt is restored and nothing more is sent. */
    public static final String INTERRUPTED = "client/interrupted";

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

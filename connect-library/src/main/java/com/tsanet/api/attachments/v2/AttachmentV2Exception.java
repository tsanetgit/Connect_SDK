package com.tsanet.api.attachments.v2;

/**
 * Any failure on the V2 attachment path: a problem-details answer from the Connect API, a
 * rejected or failed upload request, or a client-side precondition. The message is
 * value-free by construction: it carries the HTTP status, the problem type, and the
 * platform's own title and detail, never a signed URL, a header value or a token.
 *
 * <p>{@link #problemType()} is the relative {@code type} URI from the contract
 * (tsanetgit/Connect-API-Code#147), for example {@code attachment/grant-expired}; the
 * {@code UPLOAD_*} and {@code CLIENT_*} constants are this client's own types for failures
 * that never reach the platform.
 */
public class AttachmentV2Exception extends RuntimeException {

    public static final String GRANT_EXPIRED = "attachment/grant-expired";
    public static final String GRANT_ALREADY_COMPLETED = "attachment/grant-already-completed";
    public static final String UPLOAD_NOT_FOUND = "attachment/upload-not-found";
    public static final String SIZE_MISMATCH = "attachment/size-mismatch";
    public static final String CHECKSUM_MISMATCH = "attachment/checksum-mismatch";
    public static final String RECEIVER_NOT_CONFIGURED = "attachment/receiver-not-configured";
    public static final String SIZE_EXCEEDS_RECEIVER_LIMIT = "attachment/size-exceeds-receiver-limit";

    /** The storage or relay answered a signed upload request with a non-2xx status. */
    public static final String UPLOAD_REJECTED = "client/upload-rejected";
    /** The upload could not reach the storage or relay after the retry budget. */
    public static final String UPLOAD_UNREACHABLE = "client/upload-unreachable";
    /** The grant's {@code upload.mode} is not one this client implements. */
    public static final String UNSUPPORTED_UPLOAD_MODE = "client/unsupported-upload-mode";
    /** A client-side precondition failed (file size, missing part receipts, unreadable file). */
    public static final String CLIENT_PRECONDITION = "client/precondition";
    /** The Connect API could not be reached at all. */
    public static final String CONNECTIVITY = "client/connectivity";

    private final int status;
    private final String problemType;

    public AttachmentV2Exception(String message, int status, String problemType) {
        this(message, status, problemType, null);
    }

    public AttachmentV2Exception(String message, int status, String problemType, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.problemType = problemType;
    }

    /** HTTP status of the failing response, or 0 when no response was received. */
    public int status() {
        return status;
    }

    /** The relative problem type, or one of this class's {@code client/...} constants. */
    public String problemType() {
        return problemType;
    }

    /** Whether {@link #problemType()} is (or ends with) the given contract type. */
    public boolean isProblem(String type) {
        return problemType != null && (problemType.equals(type) || problemType.endsWith("/" + type));
    }
}

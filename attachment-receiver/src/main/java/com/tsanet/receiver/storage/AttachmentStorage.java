package com.tsanet.receiver.storage;

import java.io.InputStream;

/**
 * Where received attachment bytes go: one implementation per storage backend (AWS S3,
 * Azure Files, in-memory for tests). Implementations are exercised by the shared
 * {@code AttachmentStorageContractTest}; an adapter is not done until it passes it.
 *
 * <p>This SPI deliberately knows nothing about how bytes arrive. The normalized HTTPS
 * ingest endpoint is gated on tsanetgit/Connect-API-Code#140 and its wire shape must not
 * leak in here: no HTTP types, no auth, no path conventions.
 *
 * <p>Rules that bind every implementation:
 * <ul>
 *   <li><b>Streaming:</b> {@link #store} consumes the stream incrementally. It must not
 *       buffer the whole file in memory; the platform backend already does that on its
 *       side and the receiver must not repeat the mistake.</li>
 *   <li><b>Stream ownership:</b> the caller closes the stream. {@code store} must not.</li>
 *   <li><b>No partial visibility:</b> when {@code store} throws, a subsequent
 *       {@link #exists exists(caseNumber, fileName)} must return {@code false}. A failed
 *       write aborts and cleans up; it never leaves a half-written object visible.</li>
 *   <li><b>Same-name behavior is implementation-defined for now.</b> Whether a second
 *       store of the same (caseNumber, fileName) overwrites, versions, or rejects is an
 *       open contract question (tsanetgit/Connect-API-Code#140, question 2). Callers must
 *       not build a check-then-store policy on {@link #exists} — that is a race; when the
 *       answer lands, the policy moves into {@code store} itself (conditional write).</li>
 *   <li><b>{@code fileName} is remote-controlled input.</b> The platform backend appends
 *       the sender's filename to the push URL with no encoding, so it may contain path
 *       separators or traversal sequences. Adapters treat it as an opaque token, never as
 *       a path to interpret.</li>
 * </ul>
 */
public interface AttachmentStorage {

    /**
     * Streams one attachment's bytes to the backend.
     *
     * @param attachment what is being stored; {@link IncomingAttachment#contentLength()}
     *                   may be {@code -1} when the byte count is unknown up front
     * @param content    the bytes; read incrementally, never closed by this method
     * @return the backend key the bytes landed under and the count actually written
     * @throws AttachmentStorageException on any failure, including a stream that fails
     *                                    mid-read; the failed object must not be visible
     */
    StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException;

    /**
     * Whether an object for this (caseNumber, fileName) is currently visible.
     *
     * <p>An indeterminate check must throw, never answer {@code false}: on a real backend
     * this is a network call, and reporting an outage as "object absent" would make the
     * no-partial-visibility guarantee unverifiable exactly when it matters.
     *
     * @throws AttachmentStorageException when the backend cannot answer
     */
    boolean exists(String caseNumber, String fileName) throws AttachmentStorageException;

    /**
     * Go-live probe: proves the configured backend is reachable and writable by writing,
     * reading back, and deleting a sentinel object. Run on configuration save and on
     * demand; a passing probe is the precondition for registering a receive config.
     *
     * @throws AttachmentStorageException with a message specific enough to distinguish
     *                                    wrong-credential from wrong-target from
     *                                    no-permission
     */
    void verifyAccess() throws AttachmentStorageException;
}

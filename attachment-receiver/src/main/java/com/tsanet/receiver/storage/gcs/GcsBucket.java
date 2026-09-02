package com.tsanet.receiver.storage.gcs;

/**
 * The adapter's seam to one GCS bucket: the small set of operations
 * {@link GcsAttachmentStorage} uses, and nothing more. The GCS {@code Storage} type is an
 * interface, so a seam is not forced the way Azure's final clients force one; it exists
 * because {@code Storage} is a ~100-method surface and the adapter needs a handful. Keeping
 * that handful explicit lets the offline test drive a strict in-memory double that enforces
 * GCS's semantics (an object is visible only when a resumable write is finished; a missing
 * object reads as absent rather than as an error), while {@link SdkGcsBucket} stays
 * logic-free so the live contract run genuinely covers it. Package-private on purpose: this
 * is not a second SPI.
 *
 * <p>Implementations throw their SDK runtime exceptions ({@code StorageException}) through;
 * the adapter owns wrapping and classification. Keys are already-joined
 * {@code [prefix/]caseNumber/fileName} strings; GCS object names have no traversal
 * semantics, so a name with {@code /} or {@code ..} merely creates deeper name segments.
 */
interface GcsBucket {

    /**
     * Small-file path: create the object in one call from the given bytes. Overwrites an
     * existing object of the same name, matching the other adapters while same-name policy
     * stays implementation-defined upstream (tsanetgit/Connect-API-Code#140, question 2).
     */
    void create(String key, String contentType, byte[] bytes);

    /**
     * Large-file path: start a resumable upload. The object does not become visible until
     * {@link Upload#finish()}; an {@link Upload#abandon() abandoned} upload never produces a
     * visible object and the session expires server-side, which is how the SPI's
     * no-partial-visibility rule holds without an abort call.
     */
    Upload startResumable(String key, String contentType);

    /** The object's byte size, or {@code -1} when it does not exist; throws on access error. */
    long sizeOrAbsent(String key);

    byte[] download(String key);

    void delete(String key);

    /**
     * One in-progress resumable upload. Bytes appended by {@link #write} are invisible until
     * {@link #finish}; {@link #abandon} discards them without finalizing.
     */
    interface Upload {

        /** Appends {@code length} bytes from {@code buffer} to the resumable session. */
        void write(byte[] buffer, int length);

        /** Finalizes the upload, making the object visible at the bytes written so far. */
        void finish();

        /**
         * Discards the upload without finalizing, so no object becomes visible. GCS has no
         * abort call; an abandoned session simply expires, so this is best-effort local
         * release, not a server round-trip that could itself fail.
         */
        void abandon();
    }
}

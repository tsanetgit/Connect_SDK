package com.tsanet.api.attachments.v2;

import java.time.OffsetDateTime;

/**
 * A grant: permission to deliver one file on one case straight into the receiving company's
 * storage, mapped from the generated {@code AttachmentGrantDTO}. A grant carries no upload
 * links. The {@link #mode()} and {@link #plan()} say which link and complete calls apply and
 * how the file is split, and both are fixed for the grant's life.
 *
 * @param grantId           the platform's id for the grant
 * @param status            where the grant stands
 * @param fileName          the file's name as the partner will see it
 * @param expectedSizeBytes the exact byte count the upload must match
 * @param createdAt         when the grant was created
 * @param expiresAt         after this the grant can no longer be completed
 * @param mode              how the file is uploaded
 * @param plan              the part (S3) or block (Azure) layout; null for {@link UploadMode#SINGLE}
 */
public record AttachmentGrant(
    long grantId,
    Status status,
    String fileName,
    long expectedSizeBytes,
    OffsetDateTime createdAt,
    OffsetDateTime expiresAt,
    UploadMode mode,
    UploadPlan plan
) {

    /** True once the platform has completed the grant. */
    public boolean completed() {
        return status == Status.COMPLETED;
    }

    /** The grant's lifecycle, from the spec's {@code AttachmentGrantStatus}. */
    public enum Status {
        OPEN, COMPLETED, ABANDONED, EXPIRED
    }

    /**
     * How a grant's file is uploaded, from the spec's {@code AttachmentUploadMode}. This client
     * uploads {@link #SINGLE}, {@link #S3_MULTIPART} and {@link #AZURE_BLOCK}; the spec documents
     * {@link #GCS_RESUMABLE} as not available in the current release.
     */
    public enum UploadMode {
        SINGLE("single"),
        S3_MULTIPART("s3Multipart"),
        AZURE_BLOCK("azureBlock"),
        GCS_RESUMABLE("gcsResumable");

        private final String value;

        UploadMode(String value) {
            this.value = value;
        }

        /** The spec's wire value, for example {@code s3Multipart}. */
        public String value() {
            return value;
        }
    }

    /**
     * The split of a file into numbered parts (S3 multipart) or blocks (Azure). Numbers run from
     * 1 to {@code count}; every one is {@code sizeBytes} long except the last, which holds the rest.
     */
    public record UploadPlan(int count, long sizeBytes) {
    }
}

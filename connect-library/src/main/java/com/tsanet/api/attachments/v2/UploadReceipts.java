package com.tsanet.api.attachments.v2;

import java.util.List;

/**
 * What an executed upload produced: the mode that ran, the bytes sent, and for
 * {@link AttachmentGrant.UploadMode#S3_MULTIPART} the ETag of every part in part order, which
 * the S3 complete call needs. Single and Azure uploads complete without receipts.
 */
public record UploadReceipts(AttachmentGrant.UploadMode mode, long bytesSent, List<PartReceipt> parts) {

    public UploadReceipts {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** The {@code ETag} S3 answered for one part, exactly as received, quotes included. */
    public record PartReceipt(int partNumber, String etag) {
    }
}

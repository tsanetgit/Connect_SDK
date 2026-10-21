package com.tsanet.api.attachments.v2;

/**
 * A progress snapshot: parts (or blocks) done of total, and bytes sent of total. A single
 * upload reports one part. {@code bytesSent} counts the part in flight as it streams, so it
 * goes back when a part is retried.
 */
public record UploadProgress(AttachmentGrant.UploadMode mode, int partsDone, int partsTotal, long bytesSent, long bytesTotal) {
}

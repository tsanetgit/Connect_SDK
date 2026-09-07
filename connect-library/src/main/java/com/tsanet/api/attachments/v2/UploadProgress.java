package com.tsanet.api.attachments.v2;

/**
 * A progress snapshot: parts (or chunks) done of total, and bytes sent of total. Single and
 * relay uploads report one part.
 */
public record UploadProgress(String mode, int partsDone, int partsTotal, long bytesSent, long bytesTotal) {
}

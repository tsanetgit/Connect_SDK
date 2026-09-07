package com.tsanet.api.attachments.v2;

/** Receives {@link UploadProgress} snapshots as an upload advances; called on the uploading thread. */
@FunctionalInterface
public interface UploadProgressListener {
    UploadProgressListener NONE = progress -> { };

    void onProgress(UploadProgress progress);
}

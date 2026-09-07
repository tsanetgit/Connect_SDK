package com.tsanet.api.facade;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The sender's side of the V2 attachment contract (tsanetgit/Connect-API-Code#147): the
 * direct delivery path where the file goes straight into the partner's store and nothing
 * passes through the Connect API. The four calls are exposed individually for clients that
 * drive the flow themselves; {@link #send} runs the whole flow.
 *
 * <p>Every method reports the platform's recorded outcome, never this client's own
 * evidence: an upload that sent every byte is still whatever {@code complete} says it is.
 */
public interface AttachmentsV2Facade {

    /** Ask the platform for permission and upload instructions for one file on this case. */
    AttachmentGrant grant(String caseToken, AttachmentGrantRequest request);

    /**
     * Execute the grant's upload block verbatim against the file. Streams from disk one part
     * at a time; retries individual parts within the retry budget; never announces anything.
     */
    UploadReceipts upload(AttachmentGrant grant, Path file, UploadProgressListener listener);

    /** Report the upload finished; the platform seals it, verifies arrival and records the outcome. */
    AttachmentCompleteResult complete(String caseToken, UUID grantId, AttachmentCompleteRequest request);

    /** Abandon a grant; any open upload session is aborted and nothing becomes visible. */
    void abandon(String caseToken, UUID grantId);

    /**
     * Grant, upload, complete. Abandons the grant on any upload failure and rethrows; on a
     * complete-time {@code attachment/grant-expired} it re-grants exactly once and uploads
     * again. {@code withSha256} adds a streaming digest pass before the grant so the platform
     * can verify content where the target supports it.
     *
     * @param contentType the file's media type; {@code application/octet-stream} when null or blank
     * @param description optional text carried into the case note
     * @param listener    progress callback, or null
     */
    AttachmentCompleteResult send(
        String caseToken,
        Path file,
        String contentType,
        String description,
        boolean withSha256,
        UploadProgressListener listener
    );
}

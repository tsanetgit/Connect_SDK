package com.tsanet.api.facade;

import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantPage;
import com.tsanet.api.attachments.v2.UploadLink;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.nio.file.Path;
import java.util.List;

/**
 * The sender's side of V2 attachment delivery: the file goes straight into the receiving
 * company's storage and nothing passes through the Connect API. Only the case's submitting
 * company can create a grant, and its receiver is always the case's receiving company.
 *
 * <p>Each Connect API call is exposed on its own, one method per operation, for clients that
 * drive the flow themselves; {@link #upload} runs the upload for any supported mode, and
 * {@link #send} runs the whole flow. Every method returns the platform's recorded grant, never
 * this client's own reading: an upload that sent every byte is still whatever complete says.
 *
 * <p>Failures are {@link com.tsanet.api.attachments.v2.AttachmentV2Exception}, whose
 * {@code code()} names what happened.
 */
public interface AttachmentsV2Facade {

    /** Create a grant for one file. The receiver's storage decides the grant's mode and plan. */
    AttachmentGrant createGrant(String caseToken, String fileName, long expectedSizeBytes);

    AttachmentGrant getGrant(String caseToken, long grantId);

    /** One page of the case's grants; {@code page} counts from 0. */
    AttachmentGrantPage listGrants(String caseToken, int page, int size);

    /** The upload link for a {@code single} grant. Asking again signs a fresh one. */
    UploadLink singleUploadLink(String caseToken, long grantId);

    /** Links for the named parts of an {@code s3Multipart} grant, at most 1,000 per call. */
    List<UploadLink> s3PartLinks(String caseToken, long grantId, List<Integer> partNumbers);

    /** Links for the named blocks of an {@code azureBlock} grant, at most 1,000 per call. */
    List<UploadLink> azureBlockLinks(String caseToken, long grantId, List<Integer> blockNumbers);

    AttachmentGrant completeSingle(String caseToken, long grantId);

    /** Complete an S3 multipart upload; the receipts must cover every part of the plan exactly once. */
    AttachmentGrant completeS3Multipart(String caseToken, long grantId, List<UploadReceipts.PartReceipt> receipts);

    AttachmentGrant completeAzureBlock(String caseToken, long grantId);

    /** The complete call for the grant's mode, with the receipts it needs. */
    AttachmentGrant complete(String caseToken, AttachmentGrant grant, UploadReceipts receipts);

    /** Abandon a grant: no more links, no completion, nothing announced on the case. */
    AttachmentGrant abandon(String caseToken, long grantId);

    /**
     * Upload {@code file} for {@code grant}. Requests links just before use, at most 1,000 at a
     * time, refreshes a link that is about to expire or that the storage refused with 403,
     * and retries each part within its own budget. Never completes or abandons.
     */
    UploadReceipts upload(String caseToken, AttachmentGrant grant, Path file, UploadProgressListener listener);

    /**
     * Create a grant, upload, complete. A failed upload or complete abandons the grant and
     * rethrows, except a 409, where the grant is already terminal. That includes a 422 from
     * complete, although the platform leaves that grant open for another upload: a caller who
     * wants to upload again drives {@link #upload} and {@link #complete} itself. Complete is
     * retried on a 5xx answer (a 502 is the documented one) or a lost response: completing an
     * already-completed grant returns it unchanged.
     *
     * @param listener progress callback, or null
     * @return the completed grant
     */
    AttachmentGrant send(String caseToken, Path file, UploadProgressListener listener);
}

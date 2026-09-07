package com.tsanet.api.attachments.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Body of {@code POST .../grants/{grantId}/complete}: what the client uploaded, from the
 * {@code AttachmentCompleteRequestDTO} schema (tsanetgit/Connect-API-Code#147). Empty
 * collections and nulls are omitted on the wire, so a single-mode complete carries only
 * {@code sizeBytes}.
 *
 * @param sizeBytes the byte count actually sent
 * @param sha256    the digest supplied on grant, if any
 * @param parts     multipart mode only: the receipts the provider returned, in part order
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AttachmentCompleteRequest(long sizeBytes, String sha256, List<PartReceipt> parts) {
    public AttachmentCompleteRequest {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** An ETag (S3), block id (Azure) or the provider's equivalent, forwarded exactly as received. */
    public record PartReceipt(int partNumber, String receipt) {
    }
}

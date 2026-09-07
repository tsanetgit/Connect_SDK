package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import java.util.UUID;

/**
 * The three Connect API calls of the V2 attachment contract, as a seam the gateway is
 * tested against (the way the V1 gateway is tested against the generated
 * {@code CaseAttachmentsApi}). Not a second SPI: the only production implementation is
 * {@link ConnectApiAttachmentsV2Api}, and the seam disappears when the contract lands in
 * the OpenAPI spec and the generated API class takes its place.
 */
public interface AttachmentsV2Api {

    AttachmentGrant createGrant(String caseToken, AttachmentGrantRequest request, String idempotencyKey);

    AttachmentCompleteResult complete(String caseToken, UUID grantId, AttachmentCompleteRequest request);

    void abandon(String caseToken, UUID grantId);
}

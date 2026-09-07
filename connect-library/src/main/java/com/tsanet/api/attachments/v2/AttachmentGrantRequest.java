package com.tsanet.api.attachments.v2;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code POST /v2/collaboration-requests/{token}/attachments/grants}: one file the
 * sender wants to deliver to the partner on this case. Field names follow the
 * {@code AttachmentGrantRequestDTO} schema of the V2 contract draft
 * (tsanetgit/Connect-API-Code#147) exactly, so this record can be replaced by the generated
 * class once the contract lands in the OpenAPI spec.
 *
 * @param fileName    the file's name as the partner will see it (max 255)
 * @param contentType the file's media type (max 255)
 * @param sizeBytes   the declared byte count, at least 1; enforced by the signed upload
 * @param sha256      optional hex digest, verified on complete where the target supports it
 * @param description optional text carried into the case note
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentGrantRequest(
    String fileName,
    String contentType,
    long sizeBytes,
    String sha256,
    String description
) {
    public AttachmentGrantRequest {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }
        if (sizeBytes < 1) {
            throw new IllegalArgumentException("sizeBytes must be at least 1, got " + sizeBytes);
        }
    }
}

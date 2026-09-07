package com.tsanet.api.attachments.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The platform's recorded outcome for a grant, from the {@code AttachmentCompleteResultDTO}
 * schema (tsanetgit/Connect-API-Code#147). {@code status} is the platform's word, never the
 * client's: {@code DELIVERED}, {@code DELIVERED_UNVERIFIED}, {@code FAILED} or {@code EXPIRED}.
 * A client that uploaded every byte without error still reports whatever this says.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachmentCompleteResult(
    UUID grantId,
    String fileName,
    String status,
    VerificationResult verification,
    Long noteId,
    String message
) {
    public static final String DELIVERED = "DELIVERED";
    public static final String DELIVERED_UNVERIFIED = "DELIVERED_UNVERIFIED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";

    /** True only for a platform-verified delivery. */
    public boolean delivered() {
        return DELIVERED.equals(status);
    }

    /** True when the sender reported completion and the platform could not verify arrival. */
    public boolean deliveredUnverified() {
        return DELIVERED_UNVERIFIED.equals(status);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerificationResult(String method, OffsetDateTime verifiedAt, Boolean sizeMatched, Boolean checksumMatched) {
    }
}

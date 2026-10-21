package com.tsanet.api.attachments.v2;

import java.time.OffsetDateTime;

/**
 * A receiving company's registered storage configuration, mapped from the generated
 * {@code StorageConfigDTO}.
 *
 * @param target         where the company's attachments land
 * @param externalId     for an {@link StorageTarget.S3} target, the value TSANet generated for
 *                       the role's trust policy ({@code sts:ExternalId}); give it to the AWS
 *                       account's admin. It can't be set by the caller. Null for other targets
 * @param verification   the result of the last test since the configuration was registered
 * @param lastVerifiedAt when the last test ran; null when it hasn't
 */
public record StorageConfig(
    StorageTarget target,
    String externalId,
    Verification verification,
    OffsetDateTime lastVerifiedAt
) {

    /** The last test's outcome, from the spec's {@code StorageConfigVerificationStatus}. Registering resets it. */
    public enum Verification {
        NEVER_TESTED, PASSED, FAILED
    }
}

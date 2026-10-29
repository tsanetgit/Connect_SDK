package com.tsanet.e2ee;

import java.time.Instant;

/**
 * The first-receipt check of Addendum D §5 step 4: the manifest's {@code iat} is within the
 * allowed skew and age, and its {@code jti} hasn't been seen before for the sending company.
 *
 * <p>It applies only on first receipt (a webhook delivery, or the caller's own write), because
 * Connect returns the stored manifest on every later read. Mark exactly one path as first receipt
 * for each record: the webhook, or the create response, not both.
 *
 * <p>The check runs after the signature is verified, so an unsigned forgery can't fill the seen
 * set. A record that then fails a later step has still used up its {@code jti}, so a redelivery of
 * the same record is refused as a replay. When the failure was the sender's, the sender re-sends
 * with a new {@code jti}. When it was this member's (its key service unavailable at
 * {@code DECRYPT}, say), the sender never learns of it: recover by fetching the record and
 * verifying it as {@link E2eeProfile.Receipt#STORED_READ}, not by waiting for a redelivery.
 */
public interface ReplayGuard {
    enum Result {
        /** {@code iat} is within the window and {@code (iss, jti)} is new; it is now recorded. */
        FIRST,
        /** {@code (iss, jti)} was seen before. */
        REPLAYED,
        /** {@code iat} is older than the allowed age. */
        TOO_OLD,
        /** {@code iat} is later than now plus the allowed skew. */
        IN_FUTURE
    }

    /**
     * Checks the window, then atomically records {@code (iss, jti)} if it is new. Nothing outside the
     * window is recorded.
     */
    Result check(String iss, String jti, Instant iat);
}

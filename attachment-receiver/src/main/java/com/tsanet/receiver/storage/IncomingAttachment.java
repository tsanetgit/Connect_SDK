package com.tsanet.receiver.storage;

import java.util.Objects;

/**
 * What the receiver knows about one attachment before storing it. The case number is the
 * only case-correlation metadata the push contract carries, so it is required; everything
 * else degrades gracefully.
 *
 * @param caseNumber    the collaboration case number the file belongs to; never blank
 * @param fileName      the file's name as pushed; never blank
 * @param contentType   the declared media type, or {@code null} when not declared
 * @param contentLength the declared byte count, or {@code -1} when unknown (a chunked
 *                      push has no length up front; adapters must handle both)
 */
public record IncomingAttachment(String caseNumber, String fileName, String contentType,
                                 long contentLength) {

    public IncomingAttachment {
        if (Objects.requireNonNull(caseNumber, "caseNumber").isBlank()) {
            throw new IllegalArgumentException("caseNumber must not be blank");
        }
        if (Objects.requireNonNull(fileName, "fileName").isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (contentLength < -1) {
            throw new IllegalArgumentException(
                    "contentLength must be a byte count or -1 for unknown, got " + contentLength);
        }
    }

    /** Whether the byte count was declared up front. */
    public boolean lengthKnown() {
        return contentLength >= 0;
    }
}

package com.tsanet.receiver.storage;

import java.util.Objects;

/**
 * Proof of a completed store: where the bytes landed and how many were written.
 *
 * @param storageKey   the backend's key for the object; never blank, layout is the
 *                     adapter's concern
 * @param bytesWritten the count actually streamed to the backend; always the real count,
 *                     even when the incoming length was unknown
 */
public record StoredAttachment(String storageKey, long bytesWritten) {

    public StoredAttachment {
        if (Objects.requireNonNull(storageKey, "storageKey").isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (bytesWritten < 0) {
            throw new IllegalArgumentException("bytesWritten must be >= 0, got " + bytesWritten);
        }
    }
}

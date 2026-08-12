package com.tsanet.receiver.storage;

/**
 * A storage operation failed. Checked on purpose: the ingest path must map every storage
 * failure to a non-2xx response (the platform backend treats any 2xx as delivered, so a
 * swallowed failure here reads as success to the sender and the file is silently lost).
 */
public class AttachmentStorageException extends Exception {

    public AttachmentStorageException(String message) {
        super(message);
    }

    public AttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

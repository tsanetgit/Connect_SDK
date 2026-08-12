package com.tsanet.receiver.config;

/**
 * A tenant configuration could not be stored or loaded. Checked for the same reason as
 * {@code AttachmentStorageException}: a swallowed config failure upstream would let a
 * push proceed against a tenant whose configuration is unreadable.
 */
public class TenantConfigException extends Exception {

    public TenantConfigException(String message) {
        super(message);
    }

    public TenantConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

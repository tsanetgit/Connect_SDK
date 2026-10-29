package com.tsanet.e2ee;

/**
 * Encrypting a record failed: the plaintext broke a form rule, a key couldn't be found or used, or
 * signing failed. The message names the field or the company, never a value or key material.
 */
public final class E2eeException extends Exception {
    public E2eeException(String message) {
        super(message);
    }
}

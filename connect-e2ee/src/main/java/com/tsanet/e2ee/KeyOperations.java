package com.tsanet.e2ee;

import java.security.GeneralSecurityException;

/**
 * The member's two private-key operations. Everything else in the profile uses public keys, so a
 * key service (Azure Key Vault, AWS KMS, OCI Vault, an HSM) implements just these two and the
 * private keys never enter this process. {@link SoftwareKeyOperations} holds keys in memory, for
 * tests and local use.
 *
 * <p>An implementation must never put key material, or the bytes it is given, in an exception
 * message or a log line.
 */
public interface KeyOperations {
    /** The {@code kid} of this member's encryption key, exactly as its published JWKS lists it. */
    String encryptionKid();

    /** The {@code kid} of this member's signing key, exactly as its published JWKS lists it. */
    String signingKid();

    /**
     * Unwraps a content key that was wrapped with RSA-OAEP-256 (SHA-256, MGF1 with SHA-256) for
     * this member's encryption key.
     *
     * @param kid          the recipient {@code kid} the JWE named; an implementation refuses one
     *                     that isn't {@link #encryptionKid()} or a key version it still holds
     * @param encryptedKey the wrapped key
     * @return the 32-byte AES-256 content key
     */
    byte[] unwrap(String kid, byte[] encryptedKey) throws GeneralSecurityException;

    /**
     * Signs with this member's P-256 signing key (ES256).
     *
     * @param signingInput the JWS signing input, {@code BASE64URL(header) || '.' || BASE64URL(payload)},
     *                     as ASCII bytes. A key service that signs a digest hashes it with SHA-256 first
     * @return the raw 64-byte {@code r || s} signature, not DER. Azure Key Vault returns this form;
     *     AWS KMS returns DER, which its adapter converts
     */
    byte[] sign(byte[] signingInput) throws GeneralSecurityException;
}

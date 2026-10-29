package com.tsanet.e2ee;

/**
 * The outcome of verifying a received record: the decrypted content, or a refusal that names the
 * step and the field and carries no value. There is no partial result and no plaintext fallback.
 */
public sealed interface Verification<T> permits Verification.Verified, Verification.Refused {

    /** The steps of Addendum D §5, in the order they run. */
    enum Step {
        /** 1: the sending company's signing key, looked up by the manifest's {@code kid}. */
        SENDER_KEY,
        /** 2: the manifest's ES256 signature, {@code alg} and {@code typ}. */
        SIGNATURE,
        /** 3: {@code encrypted} is true, {@code ver} is supported, and {@code iss}, {@code aud} and {@code ref} match the record. */
        CLAIMS,
        /** 4, first receipt only: {@code iat} within the window and {@code jti} not seen before. */
        FIRST_RECEIPT,
        /** 5: every protected field is null or a JWE listed in the manifest, with a matching digest. */
        FIELDS,
        /** 6: this member's recipient entry, the key unwrap, the decryption and the JWE's binding. */
        DECRYPT
    }

    record Verified<T>(T content) implements Verification<T> {
        @Override
        public String toString() {
            return "Verified[" + content + "]";
        }
    }

    /**
     * @param step   the step that failed
     * @param field  the protected field's manifest name, when the failure is about one field; else null
     * @param reason a short description of the failure; never a value, ciphertext, manifest or key
     */
    record Refused<T>(Step step, String field, String reason) implements Verification<T> {
    }
}

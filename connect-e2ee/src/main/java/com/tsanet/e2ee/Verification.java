package com.tsanet.e2ee;

/**
 * The outcome of verifying a received record: the decrypted content, a refusal that names the
 * step and the field and carries no value, or the sending company's keys being unavailable. There
 * is no partial result and no plaintext fallback.
 */
public sealed interface Verification<T> permits Verification.Verified, Verification.Refused, Verification.KeysUnavailable {

    /**
     * The steps of Addendum D §5, in the order they run, except that step 3's party checks, which
     * need no key, run before step 1's key lookup.
     */
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

    /**
     * {@link PartnerKeys} threw {@code IOException} fetching the sending company's keys, at first or
     * on the refetch for an unknown {@code kid}: a failure on the caller's side, and the record wasn't
     * judged. It comes before the replay check, so no {@code jti} is used and verifying again later is
     * checked afresh. A source that throws for a company with no configured JWKS URL, or for a JWKS it
     * rejects, lands here too, and verifying again won't help until that is fixed.
     */
    record KeysUnavailable<T>() implements Verification<T> {
    }
}

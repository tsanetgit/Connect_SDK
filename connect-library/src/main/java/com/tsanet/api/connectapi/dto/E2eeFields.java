package com.tsanet.api.connectapi.dto;

/**
 * The end-to-end encryption fields a caller sends on a case or note create.
 *
 * <p>The library is a carrier for both: it sends them exactly as given, never parses, validates
 * or normalizes {@code manifest}, and does no cryptography. Encrypting the protected fields and
 * signing the manifest are the caller's job, so the summary, description and custom-field values
 * passed alongside are expected to be ciphertext already when {@code encrypted} is true.
 *
 * @param manifest  the signed manifest, a compact JWS; may be null
 * @param encrypted whether the protected fields are encrypted
 */
public record E2eeFields(String manifest, boolean encrypted) {
}

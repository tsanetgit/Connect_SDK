package com.tsanet.e2ee;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;

/**
 * Public keys by TSANet company id, this member's own included: encrypting needs the partner's
 * encryption key and this member's own (both recipients), and verifying needs the sending
 * company's signing key, which is this member's own when it re-reads a case it sent.
 *
 * <p>Keys always come from here, for the company the record says sent it, and never from a
 * manifest or a JWE. {@link PinnedJwksPartnerKeys} fetches each company's JWKS from a URL pinned in
 * configuration. Whether TSANet should relay JWKS URLs instead is undecided; that would be a second
 * implementation of this interface.
 */
public interface PartnerKeys {
    /**
     * @param companyId the TSANet company id, as a decimal string
     * @return that company's public keys; never a set holding a private key
     * @throws IOException when the keys can't be had, including for a company with no configured source
     */
    JWKSet publicKeys(String companyId) throws IOException;

    /**
     * The same, fetched again where the source caches: called once when a manifest names a
     * {@code kid} the set doesn't hold, as after the sender rotates its signing key. A source that
     * doesn't cache returns {@link #publicKeys}.
     */
    default JWKSet refreshedPublicKeys(String companyId) throws IOException {
        return publicKeys(companyId);
    }
}

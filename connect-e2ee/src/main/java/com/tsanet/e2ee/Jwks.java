package com.tsanet.e2ee;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.text.ParseException;

/** Helpers for the member's own published JWKS and for reading a partner's. */
public final class Jwks {
    private Jwks() {
    }

    /**
     * The public-only JWKS a member publishes: each key's public members ({@code kty}, {@code use},
     * {@code alg}, {@code kid}, and {@code n}/{@code e} or {@code crv}/{@code x}/{@code y}), with every
     * private member dropped.
     *
     * <p>Publish one encryption key, the current one: senders encrypt to it, and refuse a set with
     * two, since they couldn't tell which to use. Keep earlier versions in your key service for
     * records already encrypted to them; {@link KeyOperations#unwrap} gets the {@code kid} the JWE
     * named. Publish the current signing key and the earlier ones still on stored records, since
     * a receiver re-verifies a stored manifest by its {@code kid} on every read.
     */
    public static String publicJson(JWKSet keys) {
        return keys.toPublicJWKSet().toString(true);
    }

    /**
     * Parses a JWKS that should hold public keys only, and refuses one that holds any private key:
     * a published set with {@code d}, {@code p}, {@code q} or the like is a key leak, not a key source.
     */
    public static JWKSet parsePublic(String json) throws IOException {
        JWKSet set;
        try {
            set = JWKSet.parse(json);
        } catch (ParseException unparseable) {
            throw new IOException("Not a JWKS");
        }
        for (JWK key : set.getKeys()) {
            if (key.isPrivate()) {
                throw new IOException("A published JWKS must not hold private keys");
            }
        }
        return set;
    }
}

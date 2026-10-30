package com.tsanet.demo.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.tsanet.e2ee.InMemoryReplayGuard;
import com.tsanet.e2ee.Jwks;
import com.tsanet.e2ee.KeyOperations;
import com.tsanet.e2ee.ReplayGuard;
import com.tsanet.e2ee.SoftwareKeyOperations;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The demo's own E2EE keys, read once at startup from {@code TSANET_DEMO_E2EE_PRIVATE_JWKS}: one
 * private RSA encryption key (RSA-OAEP-256, 2048 bits or more) and one private P-256 signing key,
 * each with a {@code kid}. They are the demo's, not a member's: the demo signs in as its own test
 * company (tsanetgit/Connect_SDK#148), so {@link SoftwareKeyOperations} holds them in memory and
 * the hosted-connector custody question in connect-e2ee's README does not arise.
 *
 * <p>With the variable unset, {@link #enabled()} is false and nothing E2EE runs: no profile is
 * built, no JOSE call happens on any path, and the demo behaves as it did before #149. A value
 * that is set but unusable fails startup; the message names the variable and what is wrong with
 * the set, and carries no cause, so no key material reaches a log.
 *
 * <p>One key pair serves both environments. What differs per environment is the company id the
 * profile is built with and the partner table ({@link PartnerKeysStore}).
 */
@Component
public class E2eeKeys {

    public static final String VARIABLE = "TSANET_DEMO_E2EE_PRIVATE_JWKS";

    /** The specification's floor for an encryption key. */
    private static final int MIN_RSA_BITS = 2048;

    // The first-receipt window. The demo has no webhook receiver, so every record it sees is a
    // stored read and the guard is never consulted (tsanetgit/Connect_SDK#149); the window only
    // has to exist for the profile to be built.
    private static final Duration REPLAY_MAX_AGE = Duration.ofMinutes(10);
    private static final Duration REPLAY_SKEW = Duration.ofMinutes(2);

    private final KeyOperations operations;
    private final JWKSet publicKeys;
    private final String publicJson;
    private final ReplayGuard replayGuard;
    private final Clock clock = Clock.systemUTC();

    @Autowired
    public E2eeKeys(DemoE2eeProperties properties) {
        this(properties.privateJwks());
    }

    private E2eeKeys(String privateJwks) {
        if (privateJwks == null || privateJwks.isBlank()) {
            this.operations = null;
            this.publicKeys = null;
            this.publicJson = null;
            this.replayGuard = null;
            return;
        }
        JWKSet set;
        try {
            set = JWKSet.parse(privateJwks);
        } catch (ParseException notAJwks) {
            throw misconfigured("is not a JWK set");
        }
        RSAKey encryption = null;
        ECKey signing = null;
        for (JWK key : set.getKeys()) {
            if (key instanceof RSAKey rsa) {
                if (encryption != null) {
                    throw misconfigured("holds more than one RSA key; it needs exactly one encryption key");
                }
                encryption = rsa;
            } else if (key instanceof ECKey ec) {
                if (signing != null) {
                    throw misconfigured("holds more than one EC key; it needs exactly one signing key");
                }
                signing = ec;
            } else {
                throw misconfigured("holds a key that is neither RSA nor EC");
            }
        }
        if (encryption == null) {
            throw misconfigured("holds no RSA encryption key");
        }
        if (signing == null) {
            throw misconfigured("holds no P-256 signing key");
        }
        if (!encryption.isPrivate() || !signing.isPrivate()) {
            throw misconfigured("holds a key without its private part");
        }
        if (encryption.getKeyID() == null || signing.getKeyID() == null) {
            throw misconfigured("holds a key without a kid");
        }
        if (encryption.size() < MIN_RSA_BITS) {
            throw misconfigured("has an RSA key smaller than " + MIN_RSA_BITS + " bits");
        }
        if (encryption.getKeyUse() != null && !KeyUse.ENCRYPTION.equals(encryption.getKeyUse())) {
            throw misconfigured("has an RSA key whose use is not enc");
        }
        if (encryption.getAlgorithm() != null && !JWEAlgorithm.RSA_OAEP_256.equals(encryption.getAlgorithm())) {
            throw misconfigured("has an RSA key whose alg is not RSA-OAEP-256");
        }
        if (!Curve.P_256.equals(signing.getCurve())) {
            throw misconfigured("has an EC key that is not P-256");
        }
        if (signing.getKeyUse() != null && !KeyUse.SIGNATURE.equals(signing.getKeyUse())) {
            throw misconfigured("has an EC key whose use is not sig");
        }
        if (signing.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(signing.getAlgorithm())) {
            throw misconfigured("has an EC key whose alg is not ES256");
        }
        try {
            this.operations = new SoftwareKeyOperations(encryption, signing);
        } catch (JOSEException unusable) {
            throw misconfigured("holds a key Java cannot load");
        }
        this.publicKeys = set.toPublicJWKSet();
        this.publicJson = Jwks.publicJson(set);
        this.replayGuard = new InMemoryReplayGuard(REPLAY_MAX_AGE, REPLAY_SKEW, clock);
    }

    /** Keys from a private JWK set; the tests' way in (the generator's output is checked through it there). */
    public static E2eeKeys fromJson(String privateJwks) {
        return new E2eeKeys(Objects.requireNonNull(privateJwks));
    }

    /** E2EE off, as with the variable unset. */
    public static E2eeKeys disabled() {
        return new E2eeKeys((String) null);
    }

    // No cause: Spring prints the whole cause chain at startup, and a parser's message could quote
    // the value. The variable name and the rule broken are all an operator needs.
    private static IllegalStateException misconfigured(String what) {
        return new IllegalStateException(VARIABLE + " " + what
            + ". Generate a fresh set with the key generator (docs/RUNBOOK.md) and set the whole JSON.");
    }

    /** True when the variable is set: the demo encrypts, verifies and publishes its public keys. */
    public boolean enabled() {
        return operations != null;
    }

    public KeyOperations keyOperations() {
        return require(operations);
    }

    /** The public-only set, this member's own entry in every partner table. */
    public JWKSet publicKeySet() {
        return require(publicKeys);
    }

    /** The public-only set as the demo publishes it at {@code /.well-known/jwks.json}. */
    public String publicJson() {
        return require(publicJson);
    }

    public ReplayGuard replayGuard() {
        return require(replayGuard);
    }

    public Clock clock() {
        return clock;
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalStateException("E2EE is off: " + VARIABLE + " is not set");
        }
        return value;
    }

    @Override
    public String toString() {
        return "E2eeKeys[" + (enabled() ? "enabled, " + operations : "disabled") + "]";
    }
}

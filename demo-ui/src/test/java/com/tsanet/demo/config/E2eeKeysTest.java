package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.tsanet.demo.E2eeTestVectors;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The demo's own key set: what it accepts, what it refuses, and that a refusal never quotes the value. */
class E2eeKeysTest {

    private final E2eeTestVectors v = E2eeTestVectors.load();

    @Test
    void unsetMeansOff() {
        assertThat(E2eeKeys.disabled().enabled()).isFalse();
        assertThat(new E2eeKeys(new DemoE2eeProperties("  ", null)).enabled()).isFalse();
        assertThatThrownBy(() -> E2eeKeys.disabled().publicJson()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPrivateSetWithOneEncryptionAndOneSigningKeyLoads() throws Exception {
        E2eeKeys keys = E2eeKeys.fromJson(v.memberAPrivateJwks());

        assertThat(keys.enabled()).isTrue();
        assertThat(keys.keyOperations().encryptionKid()).isEqualTo(v.rsa("memberA_enc").getKeyID());
        assertThat(keys.keyOperations().signingKid()).isEqualTo(v.ec("memberA_sig").getKeyID());
        JWKSet published = JWKSet.parse(keys.publicJson());
        assertThat(published.getKeys()).hasSize(2);
        for (JWK key : published.getKeys()) {
            assertThat(key.isPrivate()).as("%s", key.getKeyID()).isFalse();
        }
        assertThat(keys.publicKeySet().getKeyByKeyId(v.rsa("memberA_enc").getKeyID()).isPrivate()).isFalse();
    }

    @Test
    void aPublicOnlySetIsRefused() {
        assertRefused(v.publicJwks("memberA"), "private part");
    }

    @Test
    void aSetWithoutASigningKeyOrWithTwoEncryptionKeysIsRefused() throws Exception {
        RSAKey enc = v.rsa("memberA_enc");
        ECKey sig = v.ec("memberA_sig");
        assertRefused(new JWKSet(List.of(enc)).toString(false), "no P-256 signing key");
        assertRefused(new JWKSet(List.of(sig)).toString(false), "no RSA encryption key");
        RSAKey second = new RSAKeyGenerator(2048).keyID("second").generate();
        assertRefused(new JWKSet(List.of(enc, second, sig)).toString(false), "more than one RSA key");
    }

    @Test
    void aWeakRsaKeyIsRefused() throws Exception {
        RSAKey weak = new RSAKeyGenerator(1024, true).keyID("weak").generate();
        assertRefused(new JWKSet(List.of(weak, v.ec("memberA_sig"))).toString(false), "smaller than 2048");
    }

    @Test
    void theWrongCurveUseAlgOrKidAndAnOctKeyAreRefused() throws Exception {
        RSAKey enc = v.rsa("memberA_enc");
        ECKey sig = v.ec("memberA_sig");
        ECKey p384 = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_384).keyID("p384").generate();
        assertRefused(new JWKSet(List.of(enc, p384)).toString(false), "not P-256");
        RSAKey rsaForSigning = new RSAKeyGenerator(2048).keyID("rsa-sig").keyUse(KeyUse.SIGNATURE).generate();
        assertRefused(new JWKSet(List.of(rsaForSigning, sig)).toString(false), "use is not enc");
        RSAKey wrongAlg = new RSAKeyGenerator(2048).keyID("rsa1-5").algorithm(JWEAlgorithm.RSA1_5).generate();
        assertRefused(new JWKSet(List.of(wrongAlg, sig)).toString(false), "alg is not RSA-OAEP-256");
        ECKey wrongSigAlg = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(Curve.P_256).keyID("es384").algorithm(JWSAlgorithm.ES384).generate();
        assertRefused(new JWKSet(List.of(enc, wrongSigAlg)).toString(false), "alg is not ES256");
        RSAKey noKid = new RSAKeyGenerator(2048).generate();
        assertRefused(new JWKSet(List.of(noKid, sig)).toString(false), "without a kid");
        OctetSequenceKey oct = new OctetSequenceKeyGenerator(256).keyID("oct").generate();
        assertRefused(new JWKSet(List.of(enc, sig, oct)).toString(false), "neither RSA nor EC");
    }

    @Test
    void notAJwksIsRefused() {
        assertRefused("{\"keys\": \"nope\"}", "not a JWK set");
        assertRefused("[1,2]", "not a JWK set");
    }

    /** The startup message names the variable and the rule; the value, private members included, is never in it. */
    private void assertRefused(String json, String why) {
        String privateD = v.rsa("memberA_enc").getPrivateExponent().toString();
        assertThatThrownBy(() -> E2eeKeys.fromJson(json))
            .isInstanceOf(IllegalStateException.class)
            .hasNoCause()
            .hasMessageContaining(E2eeKeys.VARIABLE)
            .hasMessageContaining(why)
            .satisfies(e -> {
                assertThat(e.getMessage()).doesNotContain(json.substring(0, Math.min(json.length(), 12)));
                assertThat(e.getMessage()).doesNotContain(privateD.substring(0, 16));
            });
    }
}

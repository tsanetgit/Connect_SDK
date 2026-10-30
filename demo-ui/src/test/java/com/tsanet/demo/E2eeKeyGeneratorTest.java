package com.tsanet.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.tsanet.demo.config.E2eeKeys;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class E2eeKeyGeneratorTest {

    @Test
    void aGeneratedSetIsOneTheDemoAcceptsAndFitsAnSsmStandardParameter() throws Exception {
        String json = E2eeKeyGenerator.generate();

        E2eeKeys keys = E2eeKeys.fromJson(json);
        assertThat(keys.enabled()).isTrue();
        assertThat(keys.keyOperations().encryptionKid()).startsWith("tsanet-demo-enc-");
        assertThat(keys.keyOperations().signingKid()).startsWith("tsanet-demo-sig-");
        // SSM standard tier: 4 KB. The RUNBOOK's put-parameter relies on this.
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(4096);
        // What the generator prints is private; what the demo publishes is not.
        for (JWK key : JWKSet.parse(json).getKeys()) {
            assertThat(key.isPrivate()).isTrue();
            assertThat(key.getKeyUse()).isNotNull();
            assertThat(key.getAlgorithm()).isNotNull();
        }
        for (JWK key : JWKSet.parse(keys.publicJson()).getKeys()) {
            assertThat(key.isPrivate()).isFalse();
        }
    }

    @Test
    void twoRunsGiveDifferentKeys() throws Exception {
        assertThat(E2eeKeyGenerator.generate()).isNotEqualTo(E2eeKeyGenerator.generate());
    }
}

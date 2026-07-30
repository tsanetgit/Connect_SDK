package com.tsanet.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {
    @Test
    void itVerifiesValidSignature() throws Exception {
        byte[] body = "{\"eventType\":\"collaboration-request.created\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "test-secret";
        String signature = "sha256=" + hexHmac(body, secret);

        assertThat(WebhookSignatureVerifier.verify(body, signature, secret)).isTrue();
    }

    @Test
    void itMatchesSubscriptionBySecret() throws Exception {
        byte[] body = "{\"eventType\":\"note.created\"}".getBytes(StandardCharsets.UTF_8);
        String secret = "good-secret";
        String signature = "sha256=" + hexHmac(body, secret);

        Long matched = WebhookSignatureVerifier.matchingSubscriptionId(
            body,
            signature,
            List.of(
                new WebhookSignatureVerifier.SecretCandidate(1L, "wrong"),
                new WebhookSignatureVerifier.SecretCandidate(2L, secret)
            )
        );

        assertThat(matched).isEqualTo(2L);
    }

    private static String hexHmac(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }
}

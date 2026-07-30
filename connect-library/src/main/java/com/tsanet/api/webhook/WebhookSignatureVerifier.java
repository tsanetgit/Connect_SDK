package com.tsanet.api.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignatureVerifier {
    public static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private WebhookSignatureVerifier() {
    }

    public static boolean verify(byte[] payload, String signatureHeader, String secret) {
        if (signatureHeader == null || secret == null || secret.isBlank()) {
            return false;
        }
        String expected = PREFIX + hexHmac(payload, secret);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            signatureHeader.trim().getBytes(StandardCharsets.UTF_8)
        );
    }

    public static Long matchingSubscriptionId(byte[] payload, String signatureHeader, List<SecretCandidate> secrets) {
        for (SecretCandidate candidate : secrets) {
            if (verify(payload, signatureHeader, candidate.secret())) {
                return candidate.subscriptionId();
            }
        }
        return null;
    }

    private static String hexHmac(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute webhook signature", ex);
        }
    }

    public record SecretCandidate(Long subscriptionId, String secret) {
    }
}

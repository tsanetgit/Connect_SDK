package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.e2ee.Records.CasePlaintext;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PinnedJwksConfigurationTest {
    private final Vectors v = Vectors.load();

    @Test
    void aHostlessJwksUrlIsRejectedBeforeEncrypting() {
        assertThatThrownBy(() -> {
            E2eeProfile profile = profile(Vectors.MEMBER_A, v.memberAKeys(), Vectors.MEMBER_B);
            profile.encryptCase(
                new CasePlaintext("test-case", "summary", "description", Map.of(), List.of()), Vectors.MEMBER_B);
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("A JWKS URL must have a host").hasNoCause();
    }

    @Test
    void aHostlessJwksUrlIsRejectedBeforeVerifying() {
        assertThatThrownBy(() -> {
            E2eeProfile profile = profile(Vectors.MEMBER_B, v.memberBKeys(), Vectors.MEMBER_A);
            profile.verifyCase(v.caseRecord(), E2eeProfile.Receipt.STORED_READ);
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("A JWKS URL must have a host").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https:/jwks.json?token=placeholder-secret",
        "https:jwks.json?token=placeholder-secret",
        "https:///jwks.json?token=placeholder-secret",
        "https://placeholder-user@invalid_host/jwks.json?token=placeholder-secret",
        "/jwks.json?token=placeholder-secret"
    })
    void aHostlessUrlIsRejectedByTheConstructorWithoutDisclosingItsContents(String url) {
        assertThatThrownBy(() -> new PinnedJwksPartnerKeys(Map.of("test-company", URI.create(url)), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("A JWKS URL must have a host").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://keys.example/jwks.json",
        "HTTPS://keys.example/jwks.json",
        "http://localhost/jwks.json",
        "http://127.0.0.1/jwks.json",
        "http://[::1]/jwks.json"
    })
    void supportedUrlsRemainAccepted(String url) {
        assertThatCode(() -> new PinnedJwksPartnerKeys(Map.of("test-company", URI.create(url)), Duration.ZERO))
            .doesNotThrowAnyException();
    }

    private E2eeProfile profile(String ownCompany, KeyOperations keys, String partnerCompany) {
        PartnerKeys partnerKeys = new PinnedJwksPartnerKeys(
            Map.of(partnerCompany, URI.create("https:/jwks.json?token=placeholder-secret")), Duration.ZERO);
        Clock clock = Vectors.clockAt(v.iat("example1_case_create"));
        return new E2eeProfile(ownCompany, keys, partnerKeys,
            new InMemoryReplayGuard(Duration.ofMinutes(10), Duration.ofMinutes(2), clock), clock);
    }
}

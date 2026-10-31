package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The two documented outcomes for a {@code kid} the held set lacks: which depends on the last fetch. */
class PinnedJwksUnknownKidTest {
    private final Vectors v = Vectors.load();
    private final AtomicInteger fetches = new AtomicInteger();
    private final Instant[] now = {Instant.parse("2026-10-31T00:00:00Z")};
    private volatile int status = 200;
    private HttpServer server;
    private HttpClient http;
    private E2eeProfile profile;

    @BeforeEach
    void setUp() throws Exception {
        byte[] noKeys = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            fetches.incrementAndGet();
            exchange.sendResponseHeaders(status, noKeys.length);
            exchange.getResponseBody().write(noKeys);
            exchange.close();
        });
        server.start();
        http = HttpClient.newHttpClient();
        Clock moving = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        PartnerKeys keys = new PinnedJwksPartnerKeys(
            Map.of(Vectors.MEMBER_A, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json")),
            Duration.ofMinutes(5), http, moving, Duration.ofSeconds(5));
        Clock atIat = Vectors.clockAt(v.iat("example1_case_create"));
        profile = new E2eeProfile(Vectors.MEMBER_B, v.memberBKeys(), keys,
            new InMemoryReplayGuard(Duration.ofMinutes(10), Duration.ofMinutes(2), atIat), atIat);
    }

    @AfterEach
    void tearDown() {
        http.shutdownNow();
        server.stop(0);
    }

    private Verification<?> verify() {
        return profile.verifyCase(v.caseRecord(), Receipt.STORED_READ);
    }

    @Test
    void withinAMinuteOfASuccessfulFetchAnUnknownKidIsRefusedWithoutARefetch() {
        Verification<?> result = verify();

        assertThat(result).isInstanceOf(Verification.Refused.class);
        assertThat(((Verification.Refused<?>) result).step()).isEqualTo(Verification.Step.SENDER_KEY);
        assertThat(fetches).hasValue(1);
    }

    @Test
    void duringTheWaitAfterAFailedFetchAnUnknownKidIsKeysUnavailable() {
        verify();
        now[0] = now[0].plus(Duration.ofMinutes(2));
        status = 503;

        assertThat(verify()).as("the refetch fails").isInstanceOf(Verification.KeysUnavailable.class);
        int after = fetches.get();
        assertThat(verify()).as("inside the wait").isInstanceOf(Verification.KeysUnavailable.class);
        assertThat(fetches).hasValue(after);
    }
}

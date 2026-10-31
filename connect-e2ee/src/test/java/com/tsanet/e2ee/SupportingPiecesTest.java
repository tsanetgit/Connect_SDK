package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.Base64URL;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The replay window, the sanitizer workaround, JWKS hygiene and fetching, and the signature form. */
class SupportingPiecesTest {
    private final Vectors v = Vectors.load();

    @Test
    void theReplayGuardChecksTheWindowBeforeRecordingAndRecordsOnce() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        InMemoryReplayGuard guard = new InMemoryReplayGuard(Duration.ofMinutes(10), Duration.ofMinutes(2),
            Clock.fixed(now, ZoneOffset.UTC));

        assertThat(guard.check("1042", "j1", now.minusSeconds(601))).isEqualTo(ReplayGuard.Result.TOO_OLD);
        assertThat(guard.check("1042", "j1", now.plusSeconds(121))).isEqualTo(ReplayGuard.Result.IN_FUTURE);
        assertThat(guard.check("1042", "j1", now)).as("nothing outside the window was recorded").isEqualTo(ReplayGuard.Result.FIRST);
        assertThat(guard.check("1042", "j1", now)).isEqualTo(ReplayGuard.Result.REPLAYED);
        assertThat(guard.check("2087", "j1", now)).as("jti is per sending company").isEqualTo(ReplayGuard.Result.FIRST);
        assertThat(guard.check("104", "2j1", now)).as("no collision across the split").isEqualTo(ReplayGuard.Result.FIRST);
    }

    @Test
    void theSanitizerWorkaroundRestoresOnlyASanitizedJwe() {
        String jwe = "{\"protected\":\"eyJ\",\"tag\":\"a&b\"}";
        String sanitized = jwe.replace("\"", "&#34;").replace("&b", "&amp;b");

        assertThat(SanitizerCompat.restore(sanitized)).isEqualTo(jwe);
        assertThat(SanitizerCompat.restore(jwe)).isSameAs(jwe);
        assertThat(SanitizerCompat.restore("Tom &#34;the cat&#34;")).as("plaintext is never touched").isEqualTo("Tom &#34;the cat&#34;");
        assertThat(SanitizerCompat.restore(null)).isNull();
        assertThat(SanitizerCompat.restore("{&#34;x&#x110000;&#-1;&#99999999999;"))
            .as("references it can't decode stay as they are").isEqualTo("{\"x&#x110000;&#-1;&#99999999999;");
    }

    @Test
    void aPublishedJwksDropsPrivateMembersAndAParsedOneRefusesThem() throws Exception {
        JWKSet withPrivate = new JWKSet(java.util.List.of(v.rsa("memberA_enc"), v.ec("memberA_sig")));

        String published = Jwks.publicJson(withPrivate);
        assertThat(published).doesNotContain("\"d\"").doesNotContain("\"p\"").contains(v.rsa("memberA_enc").getKeyID());
        assertThat(Jwks.parsePublic(published).getKeys()).hasSize(2);
        assertThatThrownBy(() -> Jwks.parsePublic(withPrivate.toString(false)))
            .isInstanceOf(IOException.class).hasMessageContaining("private");
    }

    @Test
    void bodiesThatParseToNullAreNotAJwks() {
        for (String body : java.util.List.of("null", "{\"keys\":[null]}")) {
            assertThatThrownBy(() -> Jwks.parsePublic(body))
                .as(body)
                .isInstanceOf(IOException.class)
                .hasMessage("Not a JWKS")
                .hasNoCause();
        }
    }

    @Test
    void pinnedJwksAreFetchedCachedAndRefusedWhenTheyHoldAPrivateKey() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/a.json", () -> { fetches.incrementAndGet(); return Jwks.publicJson(new JWKSet(v.ec("memberA_sig"))); });
        serve(server, "/leak.json", () -> new JWKSet(v.ec("memberA_sig")).toString(false));
        serve(server, "/huge.json", () -> "{\"keys\":[" + " ".repeat(70 * 1024) + "]}");
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Instant[] now = {Instant.ofEpochSecond(1_800_000_000L)};
            Clock clock = new Clock() {
                public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId zone) { return this; }
                public Instant instant() { return now[0]; }
            };
            PinnedJwksPartnerKeys keys = new PinnedJwksPartnerKeys(
                Map.of("1042", URI.create(base + "/a.json"), "666", URI.create(base + "/leak.json"),
                    "888", URI.create(base + "/huge.json")),
                Duration.ofMinutes(5), HttpClient.newHttpClient(), clock, Duration.ofSeconds(10));

            assertThat(keys.publicKeys("1042").getKeyByKeyId(v.ec("memberA_sig").getKeyID())).isNotNull();
            keys.publicKeys("1042");
            assertThat(fetches).as("served from the cache").hasValue(1);
            now[0] = now[0].plus(Duration.ofMinutes(6));
            keys.publicKeys("1042");
            assertThat(fetches).as("fetched again after the cache lifetime").hasValue(2);

            assertThatThrownBy(() -> keys.publicKeys("666")).isInstanceOf(IOException.class).hasMessageContaining("private");
            assertThatThrownBy(() -> keys.publicKeys("777")).isInstanceOf(IOException.class).hasMessageContaining("777");
            assertThatThrownBy(() -> keys.publicKeys("888")).isInstanceOf(IOException.class).hasMessageContaining("larger");

            int before = fetches.get();
            keys.refreshedPublicKeys("1042");
            assertThat(fetches).as("no refetch within a minute of the last fetch").hasValue(before);
            now[0] = now[0].plus(Duration.ofMinutes(2));
            keys.refreshedPublicKeys("1042");
            assertThat(fetches).as("a refetch once the minute has passed").hasValue(before + 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aJwksHostThatTricklesBytesTimesOut() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow.json", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            PinnedJwksPartnerKeys keys = new PinnedJwksPartnerKeys(
                Map.of("1", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow.json")),
                Duration.ZERO, HttpClient.newHttpClient(), Clock.systemUTC(), Duration.ofMillis(500));

            long start = System.nanoTime();
            assertThatThrownBy(() -> keys.publicKeys("1")).isInstanceOf(IOException.class).hasMessageContaining("longer");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aJwksUrlToAnotherHostMustBeHttps() {
        assertThatThrownBy(() -> new PinnedJwksPartnerKeys(Map.of("1", URI.create("http://keys.example/jwks.json")), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void softwareSigningGivesTheRaw64ByteFormThatVerifies() throws Exception {
        KeyOperations keys = v.memberAKeys();
        String signingInput = Base64URL.encode("{\"alg\":\"ES256\"}") + "." + Base64URL.encode("{}");

        byte[] signature = keys.sign(signingInput.getBytes(StandardCharsets.US_ASCII));

        assertThat(signature).hasSize(64);
        JWSObject jws = new JWSObject(new Base64URL(signingInput.split("\\.")[0]), new Base64URL(signingInput.split("\\.")[1]),
            Base64URL.encode(signature));
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jws.verify(new ECDSAVerifier(v.ec("memberA_sig").toPublicJWK()))).isTrue();
    }

    @Test
    void softwareUnwrapRefusesAKidThatIsNotThisMembers() throws Exception {
        assertThatThrownBy(() -> v.memberAKeys().unwrap("someone-else", new byte[256]))
            .isInstanceOf(java.security.InvalidKeyException.class);
    }

    /** A JWKS endpoint that can go down, counting every request, under a clock the test moves. */
    private final class FlakyEndpoint implements AutoCloseable {
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicBoolean up = new AtomicBoolean(true);
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(1_800_000_000L));
        final AtomicReference<Duration> failureTakes = new AtomicReference<>(Duration.ZERO);
        /** While set, a request hangs after this much clock time; {@link #hangsInBody} says at which site. */
        final AtomicReference<Duration> hangAfter = new AtomicReference<>();
        final AtomicBoolean hangsInBody = new AtomicBoolean();
        final java.util.concurrent.Semaphore hanging = new java.util.concurrent.Semaphore(0);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer server;
        final PinnedJwksPartnerKeys keys;

        FlakyEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = Jwks.publicJson(new JWKSet(v.ec("memberA_sig"))).getBytes(StandardCharsets.UTF_8);
            server.createContext("/a.json", exchange -> {
                fetches.incrementAndGet();
                Duration hang = hangAfter.get();
                if (hang != null) {
                    if (hangsInBody.get()) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                    advance(hang);
                    try {
                        // Long enough for the client to be past send and in the body read.
                        Thread.sleep(100);
                        hanging.release();
                        release.await();
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                    }
                } else if (up.get()) {
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                } else {
                    now.updateAndGet(t -> t.plus(failureTakes.get()));
                    exchange.sendResponseHeaders(503, -1);
                }
                exchange.close();
            });
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            Clock clock = new Clock() {
                public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
                public Clock withZone(java.time.ZoneId zone) { return this; }
                public Instant instant() { return now.get(); }
            };
            keys = new PinnedJwksPartnerKeys(
                Map.of("1042", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a.json")),
                Duration.ofMinutes(5), HttpClient.newHttpClient(), clock, Duration.ofSeconds(10));
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        /** A lookup the way Connect_Gateway's warm-up runs one: submitted, and cancel(true) if a request hangs. */
        void lookupCancelledIfItHangs() throws Exception {
            java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<?> lookup = callers.submit(() -> {
                try {
                    keys.publicKeys("1042");
                } catch (IOException expected) {
                    // Either the cancelled fetch or the wait; the test reads the request count.
                } finally {
                    finished.countDown();
                }
            });
            while (!finished.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (hanging.tryAcquire()) {
                    lookup.cancel(true);
                }
            }
        }

        @Override
        public void close() {
            release.countDown();
            callers.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void aFetchCancelledPastTheAgeStartsTheWaitAtEitherInterruptSite() throws Exception {
        for (boolean inBody : java.util.List.of(false, true)) {
            try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
                endpoint.hangsInBody.set(inBody);
                endpoint.hangAfter.set(PinnedJwksPartnerKeys.CANCELLED_FETCH_AGE);

                endpoint.lookupCancelledIfItHangs();
                assertThat(endpoint.fetches).as("in body: " + inBody).hasValue(1);

                assertThatThrownBy(() -> endpoint.keys.publicKeys("1042"))
                    .as("in body: " + inBody).isInstanceOf(IOException.class).hasMessageContaining("wasn't fetched");
                assertThat(endpoint.fetches).as("no request in the wait, in body: " + inBody).hasValue(1);
            }
        }
    }

    @Test
    void aFetchCancelledBeforeTheAgeDoesNotStartTheWaitAtEitherInterruptSite() throws Exception {
        for (boolean inBody : java.util.List.of(false, true)) {
            try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
                endpoint.hangsInBody.set(inBody);
                endpoint.hangAfter.set(PinnedJwksPartnerKeys.CANCELLED_FETCH_AGE.minusMillis(1));

                endpoint.lookupCancelledIfItHangs();
                assertThat(endpoint.fetches).as("in body: " + inBody).hasValue(1);

                endpoint.hangAfter.set(null);
                assertThat(endpoint.keys.publicKeys("1042").getKeyByKeyId(v.ec("memberA_sig").getKeyID()))
                    .as("a short budget doesn't block keys, in body: " + inBody).isNotNull();
                assertThat(endpoint.fetches).as("in body: " + inBody).hasValue(2);
            }
        }
    }

    @Test
    void aHangCancelledRepeatedlyAtAGatewayLikeBudgetMakesOneRequestAMinute() throws Exception {
        try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
            endpoint.hangAfter.set(Duration.ofMillis(4000));

            for (int lookup = 0; lookup < 5; lookup++) {
                endpoint.lookupCancelledIfItHangs();
            }
            assertThat(endpoint.fetches).as("five lookups in the first minute").hasValue(1);

            endpoint.advance(Duration.ofMinutes(1));
            for (int lookup = 0; lookup < 5; lookup++) {
                endpoint.lookupCancelledIfItHangs();
            }
            assertThat(endpoint.fetches).as("five more in the next").hasValue(2);
        }
    }

    @Test
    void aFailedFetchIsNotRepeatedWithinTheMinuteByEitherLookup() throws Exception {
        try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
            endpoint.up.set(false);

            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042")).hasMessageContaining("503");
            assertThat(endpoint.fetches).hasValue(1);

            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042"))
                .isInstanceOf(IOException.class).hasMessageContaining("1042").hasMessageContaining("wasn't fetched");
            assertThatThrownBy(() -> endpoint.keys.refreshedPublicKeys("1042"))
                .isInstanceOf(IOException.class).hasMessageContaining("wasn't fetched");
            assertThat(endpoint.fetches).as("no request within the minute").hasValue(1);

            endpoint.advance(Duration.ofSeconds(59));
            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042")).hasMessageContaining("wasn't fetched");
            assertThat(endpoint.fetches).as("one second short of the minute").hasValue(1);

            endpoint.advance(Duration.ofSeconds(1));
            assertThatThrownBy(() -> endpoint.keys.refreshedPublicKeys("1042")).hasMessageContaining("503");
            assertThat(endpoint.fetches).as("fetched again at the minute, and failed").hasValue(2);

            endpoint.up.set(true);
            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042")).hasMessageContaining("wasn't fetched");
            assertThat(endpoint.fetches).as("recovery isn't seen until the wait ends").hasValue(2);

            endpoint.advance(Duration.ofMinutes(1));
            assertThat(endpoint.keys.publicKeys("1042").getKeyByKeyId(v.ec("memberA_sig").getKeyID())).isNotNull();
            assertThat(endpoint.fetches).hasValue(3);
        }
    }

    @Test
    void aSetStillWithinItsLifetimeIsServedAfterAFailedRefetch() throws Exception {
        try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
            String kid = v.ec("memberA_sig").getKeyID();
            endpoint.keys.publicKeys("1042");
            endpoint.advance(Duration.ofMinutes(2));
            endpoint.up.set(false);

            assertThatThrownBy(() -> endpoint.keys.refreshedPublicKeys("1042")).hasMessageContaining("503");
            assertThat(endpoint.fetches).hasValue(2);

            assertThat(endpoint.keys.publicKeys("1042").getKeyByKeyId(kid)).as("a known kid keeps working").isNotNull();
            assertThatThrownBy(() -> endpoint.keys.refreshedPublicKeys("1042")).hasMessageContaining("wasn't fetched");
            assertThat(endpoint.fetches).as("neither lookup made a request").hasValue(2);
        }
    }

    @Test
    void theWaitRunsFromWhenTheFetchFailedNotWhenItStarted() throws Exception {
        try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
            endpoint.up.set(false);
            endpoint.failureTakes.set(Duration.ofSeconds(30));

            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042")).hasMessageContaining("503");
            endpoint.advance(Duration.ofSeconds(40));

            assertThatThrownBy(() -> endpoint.keys.publicKeys("1042")).hasMessageContaining("wasn't fetched");
            assertThat(endpoint.fetches).as("70 seconds after it started, 40 after it failed").hasValue(1);
        }
    }

    @Test
    void anInterruptedFetchDoesNotStartTheWait() throws Exception {
        try (FlakyEndpoint endpoint = new FlakyEndpoint()) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> endpoint.keys.publicKeys("1042"))
                    .isInstanceOf(IOException.class).hasMessageContaining("Interrupted");
            } finally {
                assertThat(Thread.interrupted()).as("the flag stays set for the caller").isTrue();
            }

            assertThat(endpoint.keys.publicKeys("1042").getKeyByKeyId(v.ec("memberA_sig").getKeyID())).isNotNull();
        }
    }

    private static void serve(HttpServer server, String path, java.util.function.Supplier<String> body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}

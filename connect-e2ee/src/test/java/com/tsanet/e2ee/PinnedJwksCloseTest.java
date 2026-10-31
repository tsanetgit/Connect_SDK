package com.tsanet.e2ee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.tsanet.e2ee.E2eeProfile.Receipt;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PinnedJwksCloseTest {
    private final Vectors v = Vectors.load();
    private final AtomicInteger fetches = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private volatile CountDownLatch hold;
    private final java.util.concurrent.ExecutorService handlers = Executors.newCachedThreadPool();
    private HttpServer server;
    private URI url;
    private byte[] jwks;

    @BeforeEach
    void startServer() throws Exception {
        jwks = Jwks.publicJson(v.partnerKeys().publicKeys(Vectors.MEMBER_A)).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            fetches.incrementAndGet();
            entered.countDown();
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
        url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private PinnedJwksPartnerKeys pinned() {
        return new PinnedJwksPartnerKeys(Map.of(Vectors.MEMBER_A, url), Duration.ofMinutes(5));
    }

    private E2eeProfile profileOver(PartnerKeys keys) throws Exception {
        Clock clock = Vectors.clockAt(v.iat("example1_case_create"));
        return new E2eeProfile(Vectors.MEMBER_B, v.memberBKeys(), keys,
            new InMemoryReplayGuard(Duration.ofMinutes(10), Duration.ofMinutes(2), clock), clock);
    }

    @Test
    void closeStopsTheClientsSelectorThread() throws Exception {
        Set<Long> before = selectorThreads();
        PinnedJwksPartnerKeys keys = pinned();
        Set<Long> started = selectorThreads();
        started.removeAll(before);
        assertThat(started).as("building one starts a client").isNotEmpty();

        keys.close();

        awaitGone(started);
        assertThat(started).isEmpty();
        assertThatCode(keys::close).doesNotThrowAnyException();
    }

    @Test
    void aRefusedUrlStartsNoClient() throws Exception {
        Set<Long> before = selectorThreads();

        assertThatThrownBy(() -> new PinnedJwksPartnerKeys(Map.of("1", URI.create("http://keys.partner.example/jwks")),
            Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);

        Set<Long> started = selectorThreads();
        started.removeAll(before);
        assertThat(started).isEmpty();
    }

    @Test
    void closeLeavesAClientItWasGivenRunning() throws Exception {
        HttpClient given = HttpClient.newHttpClient();
        try {
            PinnedJwksPartnerKeys keys = new PinnedJwksPartnerKeys(Map.of(Vectors.MEMBER_A, url), Duration.ofMinutes(5),
                given, Clock.systemUTC(), Duration.ofSeconds(5));

            keys.close();

            assertThat(given.isTerminated()).isFalse();
            assertThat(keys.publicKeys(Vectors.MEMBER_A).getKeys()).isNotEmpty();
        } finally {
            given.shutdownNow();
        }
    }

    @Test
    void aFetchAlreadySentFinishesAndItsSetIsReturned() throws Exception {
        hold = new CountDownLatch(1);
        PinnedJwksPartnerKeys keys = pinned();
        CompletableFuture<Integer> lookup = CompletableFuture.supplyAsync(() -> {
            try {
                return keys.publicKeys(Vectors.MEMBER_A).getKeys().size();
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture.runAsync(keys::close).get(2, TimeUnit.SECONDS);
        hold.countDown();

        assertThat(lookup.get(5, TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void aSetAlreadyHeldStillVerifiesARecordAfterClose() throws Exception {
        PinnedJwksPartnerKeys keys = pinned();
        keys.publicKeys(Vectors.MEMBER_A);
        keys.close();
        int before = fetches.get();

        Verification<?> result = profileOver(keys).verifyCase(v.caseRecord(), Receipt.STORED_READ);

        assertThat(result).isInstanceOf(Verification.Verified.class);
        assertThat(fetches).hasValue(before);
    }

    @Test
    void aValidRecordThatWouldFetchAfterCloseIsKeysUnavailableNotARefusal() throws Exception {
        PinnedJwksPartnerKeys keys = pinned();
        keys.close();

        Verification<?> result = profileOver(keys).verifyCase(v.caseRecord(), Receipt.STORED_READ);

        assertThat(result).isInstanceOf(Verification.KeysUnavailable.class);
        assertThat(fetches).hasValue(0);
    }

    private static void awaitGone(Set<Long> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!threads.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            threads.retainAll(selectorThreads());
        }
    }

    private static Set<Long> selectorThreads() {
        Set<Long> ids = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().contains("SelectorManager")) {
                ids.add(thread.threadId());
            }
        }
        return ids;
    }
}

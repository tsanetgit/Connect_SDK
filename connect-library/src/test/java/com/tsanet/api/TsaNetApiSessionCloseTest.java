package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.connectapi.dto.UserContextDto;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a caller still holding a closed session sees, on the wire (tsanetgit/Connect_SDK#139). */
class TsaNetApiSessionCloseTest {
    private static final String PASSWORD = "test-password";

    private final CountDownLatch meEntered = new CountDownLatch(1);
    private final CountDownLatch releaseMe = new CountDownLatch(1);
    private final AtomicInteger meCalls = new AtomicInteger();
    private volatile boolean holdMe;
    private HttpServer server;
    private String base;

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        releaseMe.countDown();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/v1/login")) {
            respond(exchange, "{\"accessToken\":\"bearer-1\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
        } else if (path.equals("/v1/me")) {
            meCalls.incrementAndGet();
            if (holdMe) {
                meEntered.countDown();
                try {
                    releaseMe.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, "{\"company\":{\"id\":303,\"name\":\"Sender\"},"
                + "\"user\":{\"id\":1,\"username\":\"sender@example.test\",\"email\":\"sender@example.test\"}}");
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private TsaNetApiSession signedInSession() {
        TsaNetApiSession session = TsaNetApi.initialize(new TsaNetApiConfiguration(base,
            tmp.resolve("cache.db").toString(), "default", new PasswordAuthConfig("sender@example.test", PASSWORD),
            Set.of()));
        session.auth().login("sender@example.test", PASSWORD);
        return session;
    }

    @Test
    void aRequestAlreadySentFinishesAfterTheSessionIsClosed() throws Exception {
        TsaNetApiSession session = signedInSession();
        holdMe = true;
        CompletableFuture<UserContextDto> inFlight = CompletableFuture.supplyAsync(() -> session.users().getCurrentUser());
        assertThat(meEntered.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        session.close();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).as("close doesn't wait").isLessThan(2000);
        assertThat(inFlight).isNotDone();

        releaseMe.countDown();
        assertThat(inFlight.get(5, TimeUnit.SECONDS).companyId()).isEqualTo(303L);
    }

    @Test
    void aRequestMadeAfterCloseFailsWithoutReachingTheServer() {
        TsaNetApiSession session = signedInSession();
        int before = meCalls.get();
        session.close();

        assertThatThrownBy(() -> session.users().getCurrentUser())
            .isInstanceOf(ConnectApiException.class)
            .hasMessage("cannot reach the Connect API (IOException)")
            .hasRootCauseMessage("closed")
            .extracting(failure -> ((ConnectApiException) failure).kind())
            .isEqualTo(ConnectApiException.Kind.CONNECTIVITY);
        assertThat(meCalls).hasValue(before);
    }

    @Test
    void closingTwiceDoesNothingMoreAndStoredDataStaysReadable() {
        TsaNetApiSession session = signedInSession();
        session.close();
        assertThatCode(session::close).doesNotThrowAnyException();
        assertThat(session.collaborationRequests().listStoredRequests()).isEmpty();
    }
}

package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transport against the JDK's own HTTP server standing in for a signed storage URL. What
 * the server records is the property under test: the link's headers received unchanged, the
 * wire {@code Content-Length} equal to the region (a body without a length would go chunked
 * and be refused by a store that signs the length), exactly the region's bytes, and every
 * status handed back rather than thrown.
 */
class UploadTransportTest {

    /** One received request. */
    record Hit(String method, String path, Map<String, String> headers, byte[] body) {
    }

    private HttpServer server;
    private String base;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> statusByPath = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> responseHeadersByPath = new ConcurrentHashMap<>();
    private final UploadTransport transport = new UploadTransport(HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build());

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        hits.add(new Hit(exchange.getRequestMethod(), path, headers, body));
        if (path.startsWith("/drop")) {
            exchange.close(); // no response at all: the client sees an I/O failure
            return;
        }
        responseHeadersByPath.getOrDefault(path, Map.of()).forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(statusByPath.getOrDefault(path, 201), -1);
        exchange.close();
    }

    private Path file(String name, int size) throws IOException {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        Path path = tmp.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private static byte[] region(Path file, long offset, int length) throws IOException {
        byte[] all = Files.readAllBytes(file);
        return Arrays.copyOfRange(all, (int) offset, (int) offset + length);
    }

    private UploadLink link(String path, Map<String, String> headers) {
        return new UploadLink(2, base + path, headers, null, OffsetDateTime.now().plusMinutes(30));
    }

    @Test
    void putSendsTheLinksHeadersUnchangedWithTheRegionsExactLengthAndBytes() throws Exception {
        Path file = file("region.bin", 10_000);
        Map<String, String> headers = Map.of("Content-Type", "application/octet-stream", "x-ms-blob-type", "BlockBlob",
            "Content-Length", "4000", "Host", "store.example");

        UploadTransport.PutResult result = transport.put(link("/block/2", headers), file, 3_000, 4_000, null);

        assertThat(result.status()).isEqualTo(201);
        assertThat(hits).hasSize(1);
        Hit hit = hits.get(0);
        assertThat(hit.method()).isEqualTo("PUT");
        assertThat(hit.headers()).containsEntry("Content-Type", "application/octet-stream")
            .containsEntry("x-ms-blob-type", "BlockBlob")
            .containsEntry("Content-Length", "4000");
        // Host is the JDK's to set; the link's value is filtered, not forwarded.
        assertThat(hit.headers().get("Host")).doesNotContain("store.example");
        assertThat(hit.body()).isEqualTo(region(file, 3_000, 4_000));
    }

    @Test
    void addsNoHeaderTheLinkDidNotCarry() throws Exception {
        Path file = file("bare.bin", 100);

        transport.put(link("/bare", Map.of()), file, 0, 100, null);

        assertThat(hits.get(0).headers().keySet())
            .allMatch(name -> List.of("Content-Length", "Host", "Connection", "User-Agent").stream()
                .anyMatch(name::equalsIgnoreCase), "only transport-level headers besides the link's");
    }

    @Test
    void everyStatusComesBackAsAResultAndTheEtagIsKeptExactly() throws Exception {
        Path file = file("status.bin", 10);
        statusByPath.put("/forbidden", 403);
        statusByPath.put("/busy", 503);
        statusByPath.put("/moved", 302);
        responseHeadersByPath.put("/ok", Map.of("ETag", "\"abc-123\""));
        responseHeadersByPath.put("/busy", Map.of("Retry-After", "7"));
        responseHeadersByPath.put("/moved", Map.of("Location", base + "/elsewhere"));

        assertThat(transport.put(link("/forbidden", Map.of()), file, 0, 10, null).status()).isEqualTo(403);
        UploadTransport.PutResult busy = transport.put(link("/busy", Map.of()), file, 0, 10, null);
        assertThat(busy.status()).isEqualTo(503);
        assertThat(busy.retryAfter()).contains("7");
        UploadTransport.PutResult ok = transport.put(link("/ok", Map.of()), file, 0, 10, null);
        assertThat(ok.etag()).contains("\"abc-123\"");
        // A redirect is reported, never followed.
        assertThat(transport.put(link("/moved", Map.of()), file, 0, 10, null).status()).isEqualTo(302);
        assertThat(hits).extracting(Hit::path).doesNotContain("/elsewhere");
    }

    @Test
    void aRequestThatGetsNoAnswerThrowsIoException() throws Exception {
        Path file = file("drop.bin", 10);

        assertThatThrownBy(() -> transport.put(link("/drop", Map.of()), file, 0, 10, null))
            .isInstanceOf(IOException.class);
    }

    @Test
    void anUnusableLinkFailsWithoutQuotingTheUrlOrHeaders() throws Exception {
        Path file = file("bad.bin", 10);
        UploadLink badUrl = new UploadLink(4, "not a url SENTINEL-URL", Map.of(), null, null);
        UploadLink badHeader = new UploadLink(5, base + "/x", Map.of("Bad Header", "SENTINEL-HDR"), null, null);

        assertThatThrownBy(() -> transport.put(badUrl, file, 0, 10, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("link 4")
            .hasMessageNotContaining("SENTINEL")
            .hasNoCause();
        assertThatThrownBy(() -> transport.put(badHeader, file, 0, 10, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageNotContaining("SENTINEL")
            .hasNoCause();
    }

    /**
     * The coordinator keeps the transport's IOException as the cause of client/upload-unreachable,
     * so the JDK's own failures must not quote the signed URL. These are real HttpClient failures
     * (refused, reset mid-body, unresolvable host); JDK 21 names none of the URL. A later JDK
     * that does fails here, and the cause then has to be dropped.
     */
    @Test
    void realTransportFailuresDoNotQuoteTheLink() throws Exception {
        String query = "/SENTINEL-PATH/obj?X-Amz-Signature=SENTINEL-SIG";
        Path file = file("leak.bin", 5 * 1024 * 1024);
        List<Throwable> failures = new ArrayList<>();

        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        failures.add(failureOf(() -> transport.put(new UploadLink(1, "http://127.0.0.1:" + closedPort + query,
            Map.of(), null, null), file, 0, 1024, null)));

        try (java.net.ServerSocket reset = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread resetter = new Thread(() -> {
                try (java.net.Socket client = reset.accept()) {
                    client.getInputStream().readNBytes(4096);
                    client.setSoLinger(true, 0);
                } catch (IOException ignored) {
                    // The client side is what's under test.
                }
            });
            resetter.start();
            failures.add(failureOf(() -> transport.put(new UploadLink(2, "http://127.0.0.1:" + reset.getLocalPort()
                + query, Map.of(), null, null), file, 0, 5 * 1024 * 1024, null)));
            resetter.join(10_000);
        }

        failures.add(failureOf(() -> transport.put(new UploadLink(3, "http://sentinel-host.invalid" + query,
            Map.of(), null, null), file, 0, 1024, null)));

        assertThat(failures).hasSize(3).allSatisfy(f -> assertThat(f).isInstanceOf(IOException.class));
        for (Throwable failure : failures) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage()) + " " + t).doesNotContainIgnoringCase("sentinel");
                for (Throwable suppressed : t.getSuppressed()) {
                    assertThat(String.valueOf(suppressed.getMessage()) + " " + suppressed).doesNotContainIgnoringCase("sentinel");
                }
            }
        }
    }

    @FunctionalInterface
    private interface Put {
        void run() throws Exception;
    }

    private static Throwable failureOf(Put put) {
        try {
            put.run();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    @Test
    void reportsBytesAsTheBodyStreams() throws Exception {
        int size = 3 * 1024 * 1024 + 5;
        Path file = file("big.bin", size);
        List<Long> seen = new ArrayList<>();

        transport.put(link("/big", Map.of()), file, 0, size, seen::add);

        // Once per whole MiB; the coordinator reports the finished part itself.
        assertThat(seen).containsExactly(1024L * 1024, 2L * 1024 * 1024, 3L * 1024 * 1024);
    }

    @Test
    void fileRegionPublisherDeclaresItsLengthAndReadsExactlyItsRegion() throws Exception {
        Path file = file("region.bin", 200_000);
        var publisher = new UploadTransport.FileRegionPublisher(file, 70_000, 100_000);
        assertThat(publisher.contentLength()).isEqualTo(100_000);

        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            java.util.concurrent.Flow.Subscription subscription;

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                subscription = s;
                s.request(1);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] b = new byte[item.remaining()];
                item.get(b);
                collected.writeBytes(b);
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(collected.toByteArray()).isEqualTo(region(file, 70_000, 100_000));
    }
}

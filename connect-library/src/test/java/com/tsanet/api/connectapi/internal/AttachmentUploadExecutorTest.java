package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The executor against the JDK's own HTTP server standing in for signed storage URLs. What
 * the server records is the property under test: headers received verbatim, the wire
 * {@code Content-Length} equal to the declared size (a body without a length would go
 * chunked and be refused by a store that signs the length), part order and bytes, receipts
 * forwarded intact, 4xx never retried, 5xx retried, resumable offsets recovered.
 */
class AttachmentUploadExecutorTest {

    /** One received request. */
    record Hit(String method, String path, Map<String, String> headers, byte[] body) {
    }

    private HttpServer server;
    private String base;
    private final List<Hit> hits = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> attemptsByPath = new ConcurrentHashMap<>();
    private final Map<String, Integer> failFirstAttemptsWith = new ConcurrentHashMap<>();
    private final Map<String, Boolean> dropFirstAttempt = new ConcurrentHashMap<>();
    private final AttachmentUploadExecutor executor = new AttachmentUploadExecutor(
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(), Duration.ZERO);

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
        int attempt = attemptsByPath.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        hits.add(new Hit(exchange.getRequestMethod(), path, headers, body));
        if (attempt == 1 && dropFirstAttempt.getOrDefault(path, false)) {
            exchange.close(); // no response at all: the client sees an I/O failure
            return;
        }
        Integer failWith = failFirstAttemptsWith.get(path);
        if (failWith != null && attempt == 1) {
            exchange.sendResponseHeaders(failWith, -1);
            exchange.close();
            return;
        }
        if (path.startsWith("/resumable")) {
            handleResumable(exchange, headers, body);
            return;
        }
        exchange.getResponseHeaders().add("ETag", "\"etag-" + path.substring(path.lastIndexOf('/') + 1) + "\"");
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private final Map<String, Long> resumableCommitted = new ConcurrentHashMap<>();

    private void handleResumable(HttpExchange exchange, Map<String, String> headers, byte[] body) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String range = headers.get("Content-Range");
        long committed = resumableCommitted.getOrDefault(path, 0L);
        if (range.startsWith("bytes */")) {
            // Offset query: report what is committed.
            if (committed > 0) {
                exchange.getResponseHeaders().add("Range", "bytes=0-" + (committed - 1));
            }
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
            return;
        }
        String[] spec = range.substring("bytes ".length()).split("/");
        String[] bounds = spec[0].split("-");
        long start = Long.parseLong(bounds[0]);
        long end = Long.parseLong(bounds[1]);
        long total = Long.parseLong(spec[1]);
        if (start != committed) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        committed = end + 1;
        resumableCommitted.put(path, committed);
        if (committed >= total) {
            exchange.sendResponseHeaders(200, -1);
        } else {
            exchange.getResponseHeaders().add("Range", "bytes=0-" + (committed - 1));
            exchange.sendResponseHeaders(308, -1);
        }
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

    private static AttachmentGrant grant(AttachmentGrant.Upload upload) {
        return new AttachmentGrant(UUID.randomUUID(), "f.bin", OffsetDateTime.now().plusMinutes(15),
            new AttachmentGrant.Receiver(1L, "s3", null), upload, new AttachmentGrant.Verification("platform"));
    }

    private static byte[] region(Path file, long offset, int length) throws IOException {
        byte[] all = Files.readAllBytes(file);
        return Arrays.copyOfRange(all, (int) offset, (int) offset + length);
    }

    @Test
    void singlePutSendsHeadersVerbatimWithTheExactLengthAndBytes() throws IOException {
        Path file = file("single.bin", 10_000);
        Map<String, String> headers = Map.of("Content-Type", "application/octet-stream", "x-ms-blob-type", "BlockBlob",
            "Content-Length", "10000", "Host", "store.example");
        List<UploadProgress> progress = new ArrayList<>();

        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("single", "PUT",
            base + "/single/obj", headers, null)), file, progress::add);

        assertThat(receipts.mode()).isEqualTo("single");
        assertThat(receipts.bytesSent()).isEqualTo(10_000);
        assertThat(receipts.parts()).isEmpty();
        Hit hit = hits.get(0);
        assertThat(hit.method()).isEqualTo("PUT");
        assertThat(hit.headers()).containsEntry("Content-Type", "application/octet-stream")
            .containsEntry("x-ms-blob-type", "BlockBlob")
            .containsEntry("Content-Length", "10000")
            .doesNotContainKey("Transfer-Encoding");
        assertThat(hit.headers().get("Host")).startsWith("127.0.0.1");
        assertThat(hit.body()).isEqualTo(Files.readAllBytes(file));
        assertThat(progress).containsExactly(new UploadProgress("single", 1, 1, 10_000, 10_000));
    }

    @Test
    void multipartPutsEachRegionInOrderAndForwardsReceiptsIntact() throws IOException {
        Path file = file("multi.bin", 7_000);
        List<AttachmentGrant.Part> parts = List.of(
            new AttachmentGrant.Part(2, base + "/multi/2", 2_000),
            new AttachmentGrant.Part(1, base + "/multi/1", 5_000));
        List<UploadProgress> progress = new ArrayList<>();

        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("multipart", "PUT", null,
            Map.of("Content-Type", "application/octet-stream"), parts)), file, progress::add);

        assertThat(receipts.parts()).extracting(r -> r.partNumber()).containsExactly(1, 2);
        assertThat(receipts.parts()).extracting(r -> r.receipt()).containsExactly("\"etag-1\"", "\"etag-2\"");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).path()).isEqualTo("/multi/1");
        assertThat(hits.get(0).headers()).containsEntry("Content-Length", "5000");
        assertThat(hits.get(0).body()).isEqualTo(region(file, 0, 5_000));
        assertThat(hits.get(1).path()).isEqualTo("/multi/2");
        assertThat(hits.get(1).headers()).containsEntry("Content-Length", "2000");
        assertThat(hits.get(1).body()).isEqualTo(region(file, 5_000, 2_000));
        assertThat(progress).containsExactly(
            new UploadProgress("multipart", 1, 2, 5_000, 7_000),
            new UploadProgress("multipart", 2, 2, 7_000, 7_000));
    }

    @Test
    void aTransientPartFailureIsRetriedWithAFreshBody() throws IOException {
        Path file = file("retry.bin", 3_000);
        failFirstAttemptsWith.put("/retry/1", 503);

        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("multipart", "PUT", null, Map.of(),
            List.of(new AttachmentGrant.Part(1, base + "/retry/1", 3_000)))), file, null);

        assertThat(receipts.parts()).hasSize(1);
        assertThat(attemptsByPath.get("/retry/1").get()).isEqualTo(2);
        assertThat(hits.get(1).body()).isEqualTo(Files.readAllBytes(file));
    }

    @Test
    void aRejectedPartIsNeverRetriedAndTheMessageCarriesNoUrl() throws IOException {
        Path file = file("forbidden.bin", 100);
        failFirstAttemptsWith.put("/forbidden/1", 403);
        // A 403 is what an expired signed URL answers; that is terminal for the executor.
        failFirstAttemptsWith.put("/forbidden/2", 403);

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("multipart", "PUT", null, Map.of(),
            List.of(new AttachmentGrant.Part(1, base + "/forbidden/1?sig=SECRET", 100)))), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.problemType()).isEqualTo(AttachmentV2Exception.UPLOAD_REJECTED);
                assertThat(ex.getMessage()).doesNotContain("SECRET").doesNotContain("127.0.0.1");
            });
        assertThat(attemptsByPath.get("/forbidden/1").get()).isEqualTo(1);
    }

    @Test
    void aTransientStatusAfterTheRetryBudgetIsReportedAsRejected() throws IOException {
        Path file = file("down.bin", 100);
        // Every attempt answers 503: the budget is exhausted and the last status is reported.
        server.removeContext("/");
        server.createContext("/", exchange -> {
            attemptsByPath.computeIfAbsent("/down", k -> new AtomicInteger()).incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("single", "PUT", base + "/down",
            Map.of(), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).status()).isEqualTo(503));
        assertThat(attemptsByPath.get("/down").get()).isEqualTo(AttachmentUploadExecutor.MAX_ATTEMPTS);
    }

    @Test
    void aTargetThatNeverAnswersSurfacesAsUnreachableThroughTheFacadeException() throws IOException {
        Path file = file("silent.bin", 100);
        // Every attempt is dropped without a response: the internal I/O signal must become
        // the facade's UPLOAD_UNREACHABLE, never a private exception type.
        server.removeContext("/");
        server.createContext("/", exchange -> {
            attemptsByPath.computeIfAbsent("/silent", k -> new AtomicInteger()).incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("single", "PUT", base + "/silent?sig=SECRET",
            Map.of(), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.problemType()).isEqualTo(AttachmentV2Exception.UPLOAD_UNREACHABLE);
                assertThat(ex.status()).isZero();
                assertThat(ex.getMessage()).doesNotContain("SECRET").doesNotContain("127.0.0.1");
                assertThat(ex.getCause()).isInstanceOf(IOException.class);
            });
        assertThat(attemptsByPath.get("/silent").get()).isEqualTo(AttachmentUploadExecutor.MAX_ATTEMPTS);
    }

    @Test
    void relayIsASinglePutThatIsNotRetriedOnceTheReceiverAnswered() throws IOException {
        Path file = file("relay.bin", 500);
        failFirstAttemptsWith.put("/relay", 503);

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("relay", "PUT", base + "/relay",
            Map.of("X-Grant-Token", "tok-1", "Content-Type", "application/octet-stream"), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).status()).isEqualTo(503));
        assertThat(attemptsByPath.get("/relay").get()).isEqualTo(1);
        assertThat(hits.get(0).headers()).containsEntry("X-Grant-Token", "tok-1").containsEntry("Content-Length", "500");
    }

    @Test
    void resumableSendsChunksWithContentRangeUntilTheLastOneIsAccepted() throws IOException {
        int size = AttachmentUploadExecutor.RESUMABLE_CHUNK * 2 + 1_234;
        Path file = file("resumable.bin", size);
        List<UploadProgress> progress = new ArrayList<>();

        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("resumable", "PUT",
            base + "/resumable/s1", Map.of("Content-Type", "application/octet-stream"), null)), file, progress::add);

        assertThat(receipts.mode()).isEqualTo("resumable");
        assertThat(receipts.bytesSent()).isEqualTo(size);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).headers()).containsEntry("Content-Range", "bytes 0-" + (AttachmentUploadExecutor.RESUMABLE_CHUNK - 1) + "/" + size);
        assertThat(hits.get(2).headers()).containsEntry("Content-Range",
            "bytes " + (2L * AttachmentUploadExecutor.RESUMABLE_CHUNK) + "-" + (size - 1) + "/" + size);
        assertThat(hits.get(2).body()).isEqualTo(region(file, 2L * AttachmentUploadExecutor.RESUMABLE_CHUNK, 1_234));
        assertThat(progress).extracting(UploadProgress::partsDone).containsExactly(1, 2, 3);
        assertThat(progress.get(2).bytesSent()).isEqualTo(size);
    }

    @Test
    void resumableRecoversTheCommittedOffsetAfterALostConnection() throws IOException {
        int size = AttachmentUploadExecutor.RESUMABLE_CHUNK + 999;
        Path file = file("resume-lost.bin", size);
        dropFirstAttempt.put("/resumable/s2", true);
        // The server takes the first chunk's bytes, then drops the connection without answering.
        // The executor must ask "bytes */total", learn that nothing was committed, and resend.
        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("resumable", "PUT",
            base + "/resumable/s2", Map.of(), null)), file, null);

        assertThat(receipts.bytesSent()).isEqualTo(size);
        List<String> ranges = hits.stream().map(h -> h.headers().get("Content-Range")).toList();
        assertThat(ranges).containsExactly(
            "bytes 0-" + (AttachmentUploadExecutor.RESUMABLE_CHUNK - 1) + "/" + size,
            "bytes */" + size,
            "bytes 0-" + (AttachmentUploadExecutor.RESUMABLE_CHUNK - 1) + "/" + size,
            "bytes " + AttachmentUploadExecutor.RESUMABLE_CHUNK + "-" + (size - 1) + "/" + size);
        assertThat(hits.get(3).body()).isEqualTo(region(file, AttachmentUploadExecutor.RESUMABLE_CHUNK, 999));
    }

    @Test
    void unknownModeAndMismatchedPartsFailBeforeAnyRequest() throws IOException {
        Path file = file("pre.bin", 10);

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("torrent", "PUT", base + "/x", Map.of(), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).problemType()).isEqualTo(AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE));
        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("multipart", "PUT", null, Map.of(),
            List.of(new AttachmentGrant.Part(1, base + "/x/1", 9)))), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("parts total 9 bytes but the file is 10 bytes");
        assertThat(hits).isEmpty();
    }

    @Test
    void retryAfterIsHonoredAndCapped() {
        AttachmentUploadExecutor slow = new AttachmentUploadExecutor(HttpClient.newHttpClient(), Duration.ofSeconds(1));
        assertThat(slow.backoff(1, java.util.Optional.of("2"))).isEqualTo(Duration.ofSeconds(2));
        assertThat(slow.backoff(1, java.util.Optional.of("120"))).isEqualTo(Duration.ofSeconds(30));
        // A date-formatted or malformed value falls through to exponential backoff with jitter.
        Duration fallback = slow.backoff(2, java.util.Optional.of("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertThat(fallback).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    @Test
    void relayRetriesWhenTheConnectionNeverOpens() throws IOException {
        Path file = file("relay-closed.bin", 100);
        // A closed port: every attempt fails before any byte flows, so the relay budget applies.
        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("relay", "PUT",
            "http://127.0.0.1:1/relay?sig=SECRET", Map.of(), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.problemType()).isEqualTo(AttachmentV2Exception.UPLOAD_UNREACHABLE);
                assertThat(ex.getMessage()).contains("relay upload").doesNotContain("SECRET");
                assertThat(ex.getCause()).isInstanceOf(java.net.ConnectException.class);
            });
    }

    @Test
    void aResumableSessionThatNeverReportsRangeFailsClosed() throws IOException {
        Path file = file("resume-norange.bin", 4_096);
        server.removeContext("/");
        server.createContext("/", exchange -> {
            attemptsByPath.computeIfAbsent("/norange", k -> new AtomicInteger()).incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(308, -1); // no Range: nothing committed, by the protocol
            exchange.close();
        });

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("resumable", "PUT", base + "/norange",
            Map.of(), null)), file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.problemType()).isEqualTo(AttachmentV2Exception.UPLOAD_UNREACHABLE);
                assertThat(ex.getMessage()).contains("no progress");
            });
        assertThat(attemptsByPath.get("/norange").get()).isEqualTo(AttachmentUploadExecutor.MAX_ATTEMPTS);
    }

    @Test
    void aResumableChunkAnsweredFiveHundredIsRePutWithTheSameContentRange() throws IOException {
        int size = 2_048;
        Path file = file("resume-503.bin", size);
        failFirstAttemptsWith.put("/resumable/s3", 503);

        UploadReceipts receipts = executor.execute(grant(new AttachmentGrant.Upload("resumable", "PUT",
            base + "/resumable/s3", Map.of(), null)), file, null);

        assertThat(receipts.bytesSent()).isEqualTo(size);
        List<String> ranges = hits.stream().map(h -> h.headers().get("Content-Range")).toList();
        assertThat(ranges).containsExactly("bytes 0-2047/2048", "bytes 0-2047/2048");
        assertThat(hits.get(1).body()).isEqualTo(Files.readAllBytes(file));
    }

    @Test
    void duplicatePartNumbersAreRejectedBeforeAnyRequest() throws IOException {
        Path file = file("dup.bin", 200);

        assertThatThrownBy(() -> executor.execute(grant(new AttachmentGrant.Upload("multipart", "PUT", null, Map.of(),
            List.of(new AttachmentGrant.Part(1, base + "/dup/1", 100), new AttachmentGrant.Part(1, base + "/dup/1b", 100)))),
            file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("repeats a part number");
        assertThat(hits).isEmpty();
    }

    @Test
    void singleModeReportsByteLevelProgressWhileTheBodyStreams() throws IOException {
        int size = 3 * 1024 * 1024 + 17;
        Path file = file("single-progress.bin", size);
        List<UploadProgress> progress = new ArrayList<>();

        executor.execute(grant(new AttachmentGrant.Upload("single", "PUT", base + "/single/progress", Map.of(), null)),
            file, progress::add);

        assertThat(progress.size()).isGreaterThanOrEqualTo(4);
        assertThat(progress.get(0).partsDone()).isZero();
        assertThat(progress.get(0).bytesSent()).isEqualTo(1024 * 1024);
        assertThat(progress).isSortedAccordingTo(java.util.Comparator.comparingLong(UploadProgress::bytesSent));
        assertThat(progress.get(progress.size() - 1)).isEqualTo(new UploadProgress("single", 1, 1, size, size));
    }

    @Test
    void fileRegionPublisherDeclaresItsLengthAndReadsExactlyItsRegion() throws Exception {
        Path file = file("region.bin", 200_000);
        var publisher = new AttachmentUploadExecutor.FileRegionPublisher(file, 70_000, 100_000);
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

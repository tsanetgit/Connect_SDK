package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest.PartReceipt;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadProgress;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executes a grant's {@code upload} block verbatim against a file, on the JDK's
 * {@link HttpClient} rather than the library's {@code RestTemplate}: that template wraps a
 * {@code BufferingClientHttpRequestFactory}, which would hold a whole part in memory twice
 * over, and its re-auth interceptor must never fire against a storage host.
 *
 * <ul>
 *   <li><b>single</b>: one PUT of the whole file, the grant's headers verbatim.</li>
 *   <li><b>multipart</b>: one PUT per part in part order, each streamed from its file region;
 *       the receipt is the {@code ETag} the provider answers with, forwarded as received.</li>
 *   <li><b>resumable</b>: sequential PUTs of {@value #RESUMABLE_CHUNK}-byte chunks with
 *       {@code Content-Range}, expecting 308 until the last chunk; after a connection failure
 *       the committed offset is recovered with an empty {@code Content-Range: bytes *&#47;total}
 *       query and the upload resumes there.</li>
 *   <li><b>relay</b>: the single path, but a relay PUT is a live push to the receiver, so it
 *       is retried only when the connection never opened.</li>
 * </ul>
 *
 * <p><b>Content-Length.</b> The JDK client refuses {@code Content-Length} (and {@code Host},
 * {@code Connection}, {@code Expect}, {@code Upgrade}) as explicit headers, so those are
 * filtered from the verbatim pass-through and the length is guaranteed by the body publisher
 * instead: every body here declares its exact length, which is what keeps a store that signs
 * {@code Content-Length} from refusing the PUT. The wire header is the same value the grant
 * carried.
 *
 * <p><b>Retry.</b> Per request, {@value #MAX_ATTEMPTS} attempts with exponential backoff and
 * jitter on I/O failure, 5xx and 429 ({@code Retry-After} honored, capped); any other 4xx is
 * terminal, including a 403 from an expired signed URL, which the caller surfaces as an
 * upload failure rather than re-granting on its own. Bodies are re-created per attempt from
 * the file region, so a retry never re-reads a consumed stream.
 *
 * <p><b>URL scheme.</b> Upload URLs are executed as the platform returned them, {@code http}
 * included: the contract's rule is that the client executes the block verbatim and never
 * branches on the receiver, and these URLs come from the authenticated platform per grant,
 * short-lived, not from operator configuration (where this repo does enforce https, since a
 * pasted SAS URL is a standing credential). Rejecting a scheme here would also reject the
 * relay and any test host the platform chooses to point at.
 *
 * <p>Nothing here announces anything: the caller completes or abandons the grant.
 */
final class AttachmentUploadExecutor {

    static final int RESUMABLE_CHUNK = 8 * 1024 * 1024;
    static final int MAX_ATTEMPTS = 3;
    static final int READ_BUFFER = 64 * 1024;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
    private static final Set<String> RESTRICTED_HEADERS = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final HttpClient http;
    private final Duration baseBackoff;

    AttachmentUploadExecutor() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(30))
                .build(),
            Duration.ofSeconds(1));
    }

    AttachmentUploadExecutor(HttpClient http, Duration baseBackoff) {
        this.http = http;
        this.baseBackoff = baseBackoff;
    }

    UploadReceipts execute(AttachmentGrant grant, Path file, UploadProgressListener listener) {
        AttachmentGrant.Upload upload = grant.upload();
        if (upload == null || upload.mode() == null) {
            throw precondition("grant carries no upload block");
        }
        UploadProgressListener progress = listener == null ? UploadProgressListener.NONE : listener;
        long size = sizeOf(file);
        Map<String, String> headers = passThrough(upload.headers());
        String mode = upload.mode().toLowerCase(Locale.ROOT);
        try {
            return switch (mode) {
                case "single" -> single(upload, headers, file, size, progress, false);
                case "relay" -> single(upload, headers, file, size, progress, true);
                case "multipart" -> multipart(upload, headers, file, size, progress);
                case "resumable" -> resumable(upload, headers, file, size, progress);
                default -> throw new AttachmentV2Exception("upload mode '" + upload.mode()
                    + "' is not implemented by this client", 0, AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE);
            };
        } catch (UploadIoFailure io) {
            // The one boundary where the internal I/O signal becomes the facade's exception.
            throw new AttachmentV2Exception(io.getMessage(), 0, AttachmentV2Exception.UPLOAD_UNREACHABLE, io.getCause());
        }
    }

    private UploadReceipts single(AttachmentGrant.Upload upload, Map<String, String> headers, Path file, long size,
                                  UploadProgressListener progress, boolean relay) {
        String mode = relay ? "relay" : "single";
        requireUrl(upload.url(), mode);
        // Byte-level progress as the body streams (position within the region, so a retry
        // truthfully restarts from zero), throttled to whole MiB; the final report says done.
        FileRegionPublisher body = new FileRegionPublisher(file, 0, size, sent ->
            progress.onProgress(new UploadProgress(mode, 0, 1, sent, size)));
        HttpRequest request = put(upload.url(), headers, body, size);
        sendWithRetry(request, relay ? RetryScope.CONNECT_ONLY : RetryScope.TRANSIENT, mode + " upload");
        progress.onProgress(new UploadProgress(mode, 1, 1, size, size));
        return new UploadReceipts(mode, size, List.of());
    }

    private UploadReceipts multipart(AttachmentGrant.Upload upload, Map<String, String> headers, Path file, long size,
                                     UploadProgressListener progress) {
        List<AttachmentGrant.Part> parts = new ArrayList<>(upload.parts());
        if (parts.isEmpty()) {
            throw precondition("multipart grant carries no parts");
        }
        parts.sort(Comparator.comparingInt(AttachmentGrant.Part::partNumber));
        if (parts.stream().mapToInt(AttachmentGrant.Part::partNumber).distinct().count() != parts.size()) {
            throw precondition("multipart grant repeats a part number; the regions would overlap");
        }
        long declared = parts.stream().mapToLong(AttachmentGrant.Part::sizeBytes).sum();
        if (declared != size) {
            throw precondition("multipart parts total " + declared + " bytes but the file is " + size + " bytes");
        }
        List<PartReceipt> receipts = new ArrayList<>(parts.size());
        long offset = 0;
        for (AttachmentGrant.Part part : parts) {
            requireUrl(part.url(), "multipart part " + part.partNumber());
            HttpRequest request = put(part.url(), headers, new FileRegionPublisher(file, offset, part.sizeBytes()), part.sizeBytes());
            HttpResponse<Void> response = sendWithRetry(request, RetryScope.TRANSIENT, "part " + part.partNumber());
            String receipt = response.headers().firstValue("ETag")
                .orElseThrow(() -> new AttachmentV2Exception("part " + part.partNumber()
                    + " was accepted without an ETag; the platform cannot seal it", response.statusCode(),
                    AttachmentV2Exception.CLIENT_PRECONDITION));
            receipts.add(new PartReceipt(part.partNumber(), receipt));
            offset += part.sizeBytes();
            progress.onProgress(new UploadProgress("multipart", receipts.size(), parts.size(), offset, size));
        }
        return new UploadReceipts("multipart", size, receipts);
    }

    private UploadReceipts resumable(AttachmentGrant.Upload upload, Map<String, String> headers, Path file, long size,
                                     UploadProgressListener progress) {
        requireUrl(upload.url(), "resumable");
        int chunks = (int) ((size + RESUMABLE_CHUNK - 1) / RESUMABLE_CHUNK);
        long offset = 0;
        int done = 0;
        int stalls = 0;
        while (offset < size) {
            long length = Math.min(RESUMABLE_CHUNK, size - offset);
            long end = offset + length - 1;
            Map<String, String> chunkHeaders = new LinkedHashMap<>(headers);
            chunkHeaders.put("Content-Range", "bytes " + offset + "-" + end + "/" + size);
            HttpRequest request = put(upload.url(), chunkHeaders, new FileRegionPublisher(file, offset, length), length);
            long nextOffset;
            try {
                HttpResponse<Void> response = sendWithRetry(request, RetryScope.TRANSIENT_STATUS_ONLY, "resumable chunk at " + offset);
                // A 308 says how much the session holds in its Range header. No Range means
                // nothing is committed (the resumable-protocol reading); treating it as a full
                // chunk would skip bytes silently, so it counts as no progress and the chunk is
                // resent, and a session that never reports Range fails closed after the budget.
                nextOffset = response.statusCode() == 308 ? committedOffset(response, offset) : size;
            } catch (UploadIoFailure io) {
                // The session may have taken some or all of the chunk: ask where it stands.
                nextOffset = queryCommittedOffset(upload.url(), headers, size, io);
            }
            if (nextOffset <= offset) {
                // The session took none of that chunk (a dropped connection before commit):
                // resend it, within the same budget a failed part gets.
                if (++stalls >= MAX_ATTEMPTS) {
                    throw new AttachmentV2Exception("resumable session made no progress at offset " + offset
                        + " after " + stalls + " attempts", 0, AttachmentV2Exception.UPLOAD_UNREACHABLE);
                }
                continue;
            }
            stalls = 0;
            offset = nextOffset;
            done = (int) Math.min(chunks, (offset + RESUMABLE_CHUNK - 1) / RESUMABLE_CHUNK);
            progress.onProgress(new UploadProgress("resumable", done, chunks, offset, size));
        }
        return new UploadReceipts("resumable", size, List.of());
    }

    /** {@code PUT} with {@code Content-Range: bytes *&#47;total} and no body: 308 + Range tells the committed offset. */
    private long queryCommittedOffset(String url, Map<String, String> headers, long size, UploadIoFailure cause) {
        Map<String, String> queryHeaders = new LinkedHashMap<>(headers);
        queryHeaders.put("Content-Range", "bytes */" + size);
        HttpRequest query = put(url, queryHeaders, HttpRequest.BodyPublishers.noBody(), 0);
        HttpResponse<Void> response;
        try {
            response = http.send(query, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            cause.addSuppressed(e);
            throw new AttachmentV2Exception("resumable upload lost its connection and the session could not be queried",
                0, AttachmentV2Exception.UPLOAD_UNREACHABLE, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentV2Exception("interrupted while querying the resumable session", 0,
                AttachmentV2Exception.UPLOAD_UNREACHABLE, e);
        }
        if (response.statusCode() == 308) {
            return committedOffset(response, 0);
        }
        if (response.statusCode() / 100 == 2) {
            return size;
        }
        throw rejected("resumable session query", response.statusCode());
    }

    /** {@code Range: bytes=0-N} on a 308 means N+1 bytes are committed; no Range means {@code fallback}. */
    private static long committedOffset(HttpResponse<?> response, long fallback) {
        Optional<String> range = response.headers().firstValue("Range");
        if (range.isEmpty()) {
            return fallback;
        }
        String value = range.get().trim();
        int dash = value.lastIndexOf('-');
        if (dash < 0) {
            return fallback;
        }
        try {
            return Long.parseLong(value.substring(dash + 1).trim()) + 1;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private enum RetryScope { TRANSIENT, TRANSIENT_STATUS_ONLY, CONNECT_ONLY }

    /** Thrown internally when a request could not complete over the network after its budget. */
    private static final class UploadIoFailure extends RuntimeException {
        UploadIoFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private HttpResponse<Void> sendWithRetry(HttpRequest request, RetryScope scope, String what) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<Void> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException e) {
                last = e;
                boolean connectLevel = e instanceof ConnectException || e instanceof HttpConnectTimeoutException;
                boolean retryable = switch (scope) {
                    case TRANSIENT -> true;
                    case TRANSIENT_STATUS_ONLY -> false;
                    case CONNECT_ONLY -> connectLevel;
                };
                if (!retryable || attempt == MAX_ATTEMPTS) {
                    throw new UploadIoFailure(what + " could not reach the upload target: " + e.getClass().getSimpleName(), e);
                }
                sleep(backoff(attempt, Optional.empty()));
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AttachmentV2Exception("interrupted during " + what, 0, AttachmentV2Exception.UPLOAD_UNREACHABLE, e);
            }
            int status = response.statusCode();
            if (status / 100 == 2 || (status == 308 && scope == RetryScope.TRANSIENT_STATUS_ONLY)) {
                return response;
            }
            // A relay PUT is a live push: once the receiver answered, re-sending could duplicate it.
            boolean transientStatus = (status == 429 || status / 100 == 5) && scope != RetryScope.CONNECT_ONLY;
            if (transientStatus && attempt < MAX_ATTEMPTS) {
                sleep(backoff(attempt, response.headers().firstValue("Retry-After")));
                continue;
            }
            throw rejected(what, status);
        }
        throw new UploadIoFailure(what + " exhausted its retry budget", last);
    }

    private static AttachmentV2Exception rejected(String what, int status) {
        return new AttachmentV2Exception(what + " was rejected by the upload target: HTTP " + status, status,
            AttachmentV2Exception.UPLOAD_REJECTED);
    }

    Duration backoff(int attempt, Optional<String> retryAfter) {
        if (retryAfter.isPresent()) {
            try {
                long seconds = Long.parseLong(retryAfter.get().trim());
                return Duration.ofSeconds(Math.min(seconds, MAX_RETRY_AFTER.toSeconds()));
            } catch (NumberFormatException ignored) {
                // Fall through to exponential backoff for date-formatted values.
            }
        }
        long millis = baseBackoff.toMillis() * (1L << (attempt - 1));
        long jitter = millis == 0 ? 0 : ThreadLocalRandom.current().nextLong(millis / 2 + 1);
        return Duration.ofMillis(millis + jitter);
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentV2Exception("interrupted while backing off", 0, AttachmentV2Exception.UPLOAD_UNREACHABLE, e);
        }
    }

    private static HttpRequest put(String url, Map<String, String> headers, HttpRequest.BodyPublisher body, long bytes) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeoutFor(bytes))
            .PUT(body);
        headers.forEach(builder::header);
        return builder.build();
    }

    /** One minute plus one second per 512 KiB: generous for a slow uplink, finite for a stuck one. */
    static Duration timeoutFor(long bytes) {
        return Duration.ofSeconds(60 + bytes / (512 * 1024));
    }

    /** The grant's headers minus the ones the JDK client reserves; the length rides on the body instead. */
    static Map<String, String> passThrough(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (name != null && value != null && !RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(name, value);
            }
        });
        return out;
    }

    private static void requireUrl(String url, String what) {
        if (url == null || url.isBlank()) {
            throw precondition(what + " carries no url");
        }
    }

    private static long sizeOf(Path file) {
        try {
            long size = Files.size(file);
            if (size < 1) {
                throw precondition("file is empty; the contract requires at least one byte");
            }
            return size;
        } catch (IOException e) {
            throw new AttachmentV2Exception("cannot read the file to upload: " + e.getClass().getSimpleName(), 0,
                AttachmentV2Exception.CLIENT_PRECONDITION, e);
        }
    }

    private static AttachmentV2Exception precondition(String message) {
        return new AttachmentV2Exception(message, 0, AttachmentV2Exception.CLIENT_PRECONDITION);
    }

    /**
     * A body publisher over one region of a file, with an exact {@link #contentLength()}. Each
     * subscription opens its own channel, so the same request can be re-sent on retry.
     * Single-subscriber, demand-driven, {@value #READ_BUFFER}-byte reads.
     */
    static final class FileRegionPublisher implements HttpRequest.BodyPublisher {
        private static final long PROGRESS_STEP = 1024 * 1024;

        private final Path file;
        private final long offset;
        private final long length;
        private final java.util.function.LongConsumer onBytes;

        FileRegionPublisher(Path file, long offset, long length) {
            this(file, offset, length, null);
        }

        /** {@code onBytes} receives the bytes read so far in this subscription, once per whole MiB. */
        FileRegionPublisher(Path file, long offset, long length, java.util.function.LongConsumer onBytes) {
            this.file = file;
            this.offset = offset;
            this.length = length;
            this.onBytes = onBytes;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            FileChannel channel;
            try {
                channel = FileChannel.open(file, StandardOpenOption.READ);
            } catch (IOException e) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(e);
                return;
            }
            subscriber.onSubscribe(new RegionSubscription(channel, subscriber));
        }

        private final class RegionSubscription implements Flow.Subscription {
            private final FileChannel channel;
            private final Flow.Subscriber<? super ByteBuffer> subscriber;
            private final AtomicLong demand = new AtomicLong();
            private final AtomicInteger wip = new AtomicInteger();
            private long position = offset;
            private long remaining = length;
            private volatile boolean cancelled;
            private boolean terminated;

            RegionSubscription(FileChannel channel, Flow.Subscriber<? super ByteBuffer> subscriber) {
                this.channel = channel;
                this.subscriber = subscriber;
            }

            @Override
            public void request(long n) {
                if (n <= 0) {
                    fail(new IllegalArgumentException("non-positive request: " + n));
                    return;
                }
                demand.addAndGet(n);
                drain();
            }

            @Override
            public void cancel() {
                cancelled = true;
                close();
            }

            private void drain() {
                if (wip.getAndIncrement() != 0) {
                    return;
                }
                int missed = 1;
                do {
                    while (!cancelled && !terminated && remaining > 0 && demand.get() > 0) {
                        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(READ_BUFFER, remaining));
                        int read;
                        try {
                            read = channel.read(buffer, position);
                        } catch (IOException e) {
                            fail(e);
                            return;
                        }
                        if (read <= 0) {
                            fail(new IOException("file ended " + remaining + " bytes before the declared region did"));
                            return;
                        }
                        long before = position - offset;
                        position += read;
                        remaining -= read;
                        buffer.flip();
                        demand.decrementAndGet();
                        subscriber.onNext(buffer);
                        long after = position - offset;
                        if (onBytes != null && after / PROGRESS_STEP != before / PROGRESS_STEP) {
                            onBytes.accept(after);
                        }
                    }
                    if (!cancelled && !terminated && remaining == 0) {
                        terminated = true;
                        close();
                        subscriber.onComplete();
                    }
                    missed = wip.addAndGet(-missed);
                } while (missed != 0);
            }

            private void fail(Throwable error) {
                if (terminated || cancelled) {
                    return;
                }
                terminated = true;
                close();
                subscriber.onError(error);
            }

            private void close() {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Nothing useful to do with a close failure on a read-only channel.
                }
            }
        }
    }
}

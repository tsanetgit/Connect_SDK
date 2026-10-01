package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * One upload {@code PUT} of a file region to a signed link, on the JDK's {@link HttpClient}
 * rather than the library's {@code RestTemplate}: that template wraps a
 * {@code BufferingClientHttpRequestFactory}, which would hold a whole part in memory twice
 * over, and its re-auth interceptor must never fire against a storage host. Redirects are
 * never followed.
 *
 * <p>The transport reports the storage's answer and decides nothing: which statuses to retry,
 * when to ask for a fresh link and when to give up belong to {@link UploadCoordinator}.
 *
 * <p><b>Headers.</b> The link's headers are sent unchanged, and this class adds none of its
 * own; the JDK client sets {@code Host}, {@code Content-Length}, {@code Connection} and
 * {@code User-Agent} on every request. It refuses {@code Content-Length} (and {@code Host},
 * {@code Connection}, {@code Expect}, {@code Upgrade}) as explicit headers, so those are
 * filtered from the link's and the length is guaranteed by the body publisher instead: every
 * body here declares its exact length, which keeps a store that signs {@code Content-Length}
 * from refusing the PUT.
 */
class UploadTransport {

    static final int READ_BUFFER = 64 * 1024;
    private static final Set<String> RESTRICTED_HEADERS = Set.of("content-length", "host", "connection", "expect", "upgrade");

    private final HttpClient http;

    UploadTransport() {
        this(HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build());
    }

    UploadTransport(HttpClient http) {
        this.http = http;
    }

    /** The storage's answer to one {@code PUT}: its status, and the two headers the coordinator reads. */
    record PutResult(int status, Optional<String> etag, Optional<String> retryAfter) {
    }

    /**
     * {@code PUT} {@code length} bytes of {@code file} from {@code offset} to {@code link}. Any
     * status comes back as a result; only a request that got no answer throws.
     *
     * @param onBytes receives the bytes sent so far in this request, once per whole MiB; may be null
     * @throws IOException when no answer arrived
     */
    PutResult put(UploadLink link, Path file, long offset, long length, LongConsumer onBytes)
        throws IOException, InterruptedException {
        HttpRequest request = request(link, new FileRegionPublisher(file, offset, length, onBytes), length);
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        return new PutResult(response.statusCode(), response.headers().firstValue("ETag"),
            response.headers().firstValue("Retry-After"));
    }

    private static HttpRequest request(UploadLink link, HttpRequest.BodyPublisher body, long bytes) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(link.url()))
                .timeout(timeoutFor(bytes))
                .PUT(body);
            passThrough(link.headers()).forEach(builder::header);
            return builder.build();
        } catch (IllegalArgumentException e) {
            // The JDK's message quotes the offending URL or header, and a link is a credential:
            // name the link by its number and drop the cause.
            throw new AttachmentV2Exception("link " + link.number() + " is not a usable upload request", 0,
                AttachmentV2Exception.CLIENT_PRECONDITION);
        }
    }

    /** One minute plus one second per 512 KiB: generous for a slow uplink, finite for a stuck one. */
    static Duration timeoutFor(long bytes) {
        return Duration.ofSeconds(60 + bytes / (512 * 1024));
    }

    /** The link's headers minus the ones the JDK client reserves; the length rides on the body instead. */
    static Map<String, String> passThrough(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            if (name != null && value != null && !RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(name, value);
            }
        });
        return out;
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

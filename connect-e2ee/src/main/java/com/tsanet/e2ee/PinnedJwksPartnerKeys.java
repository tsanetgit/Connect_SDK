package com.tsanet.e2ee;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PartnerKeys} from a JWKS URL pinned per company in configuration, fetched over HTTPS (plain
 * HTTP only to this machine, for tests and a local key server) and cached. A fetch that fails, answers anything but 200, or returns a set with a private key is
 * refused; a cached set is used until it is older than the cache lifetime.
 *
 * <p>After a failed fetch, a lookup that would fetch that company's set throws instead for the next
 * minute, whatever the cache lifetime, so an endpoint that is down gets one request a minute per
 * company. A set still within its lifetime is served as usual. Lookups running at the same moment
 * don't share a fetch, so each can fetch until the first failure is recorded. A fetch that was
 * interrupted counts as failed once it has run for two seconds, so an endpoint that hangs is limited
 * too, even with callers that cancel inside the fetch timeout. Interrupted sooner, it records no
 * failure, so a hang is limited only for callers whose budget is at least that long.
 *
 * <p>An instance holds a JDK {@code HttpClient}, with its selector thread, from the moment it's built.
 * {@link #close} one you drop, for example when the JWKS URLs change. {@link PartnerKeys} has no
 * close, since other sources may hold nothing, so whoever builds this instance owns closing it.
 */
public final class PinnedJwksPartnerKeys implements PartnerKeys, AutoCloseable {
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_JWKS_BYTES = 64 * 1024;
    // Both the refetch limit after a success and the wait after a failure.
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    // A fetch cancelled after this long counts as failed. Well above a normal fetch of a small JSON
    // document, so a caller's short budget can't block a healthy endpoint's keys for a minute, and
    // low enough that a caller cancelling at a budget of a few seconds still counts.
    static final Duration CANCELLED_FETCH_AGE = Duration.ofSeconds(2);
    // Virtual threads, so a stalled read never holds a pool thread; it ends when the stream is closed.
    private static final java.util.concurrent.ExecutorService READERS =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, URI> jwksUrls;
    private final Duration cacheLifetime;
    private final HttpClient http;
    private final Clock clock;
    private final Duration fetchTimeout;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();
    private final boolean ownsClient;

    private record Cached(JWKSet keys, Instant fetchedAt) {
    }

    /**
     * @param jwksUrls      each company id's JWKS URL, this member's own included; HTTPS only
     * @param cacheLifetime how long a fetched set is used before it is fetched again. Shorter picks
     *                      up a partner's key rotation sooner
     * @throws IllegalArgumentException if a URL has no host or uses an unsupported scheme
     */
    public PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime) {
        // Checked as the first argument, so a refused URL never starts a client that nothing would stop.
        this(checked(jwksUrls), cacheLifetime, HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build(),
            Clock.systemUTC(), FETCH_TIMEOUT, true);
    }

    /** With a client the caller owns, which {@link #close} leaves running. */
    PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime, HttpClient http, Clock clock,
                          Duration fetchTimeout) {
        this(checked(jwksUrls), cacheLifetime, http, clock, fetchTimeout, false);
    }

    private PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime, HttpClient http, Clock clock,
                                  Duration fetchTimeout, boolean ownsClient) {
        this.jwksUrls = jwksUrls;
        this.cacheLifetime = cacheLifetime;
        this.http = http;
        this.clock = clock;
        this.fetchTimeout = fetchTimeout;
        this.ownsClient = ownsClient;
    }

    private static Map<String, URI> checked(Map<String, URI> jwksUrls) {
        for (URI url : jwksUrls.values()) {
            if (url.getHost() == null) {
                throw new IllegalArgumentException("A JWKS URL must have a host");
            }
            if (!"https".equalsIgnoreCase(url.getScheme()) && !isLoopback(url)) {
                throw new IllegalArgumentException("A JWKS URL must be HTTPS");
            }
        }
        // The copy too, since it refuses a null key: everything that can refuse runs before the client starts.
        return Map.copyOf(jwksUrls);
    }

    /**
     * Stops the client this instance built. It doesn't wait: a fetch already sent runs to completion
     * and its set is returned. After that, a lookup a held set answers is still answered, and one that
     * would fetch throws {@code IOException}, which {@code E2eeProfile} reports as
     * {@code KeysUnavailable}, so closing can't turn a valid record into a refusal. Closing twice
     * does nothing more.
     */
    // shutdown, not close: close waits for fetches in flight, which would stall replacing an instance.
    @Override
    public void close() {
        if (ownsClient) {
            http.shutdown();
        }
    }

    // Plain HTTP only to this machine, for tests and a local key server; never to another host.
    private static boolean isLoopback(URI url) {
        String host = url.getHost();
        return "http".equalsIgnoreCase(url.getScheme())
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
    }

    /**
     * Fetches again, unless this company's set was fetched within the last minute: after a partner
     * rotates its signing key, its first manifest under the new {@code kid} isn't refused for the
     * rest of the cache lifetime, and a sender naming unknown kids can't make every read a fetch. A
     * fetch for this company that failed within the last minute makes this throw without fetching,
     * so that holds while the endpoint is down too.
     */
    @Override
    public JWKSet refreshedPublicKeys(String companyId) throws IOException {
        URI url = jwksUrls.get(companyId);
        if (url == null) {
            throw new IOException("No JWKS URL is configured for company " + companyId);
        }
        Instant now = clock.instant();
        Cached cached = cache.get(companyId);
        if (cached != null && cached.fetchedAt().plus(MIN_REFRESH_INTERVAL).isAfter(now)) {
            return cached.keys();
        }
        return fetchAndCache(url, companyId, now);
    }

    @Override
    public JWKSet publicKeys(String companyId) throws IOException {
        URI url = jwksUrls.get(companyId);
        if (url == null) {
            throw new IOException("No JWKS URL is configured for company " + companyId);
        }
        Instant now = clock.instant();
        Cached cached = cache.get(companyId);
        if (cached != null && cached.fetchedAt().plus(cacheLifetime).isAfter(now)) {
            return cached.keys();
        }
        return fetchAndCache(url, companyId, now);
    }

    // The one place a lookup reaches the network, so the wait after a failure applies only where a
    // fetch would happen. Lookups don't share a fetch: one that started first can fail just after
    // another succeeds, starting a wait next to a fresh set. That set is still served; once it's a
    // minute old, a refetch for an unknown kid in that wait reads as KeysUnavailable, which is retryable.
    private JWKSet fetchAndCache(URI url, String companyId, Instant now) throws IOException {
        Instant failedAt = lastFailure.get(companyId);
        if (failedAt != null && failedAt.plus(MIN_REFRESH_INTERVAL).isAfter(now)) {
            throw new IOException("The JWKS for company " + companyId + " wasn't fetched: a fetch failed within the last minute");
        }
        JWKSet fetched;
        try {
            fetched = fetch(url, companyId);
        } catch (IOException failed) {
            // An early interrupt is this caller's short budget, not the partner's failure. Timed when
            // the fetch ended, or a slow failure would use up the wait while it ran.
            Instant ended = clock.instant();
            if (!Thread.currentThread().isInterrupted() || !ended.isBefore(now.plus(CANCELLED_FETCH_AGE))) {
                lastFailure.put(companyId, ended);
            }
            throw failed;
        }
        cache.put(companyId, new Cached(fetched, now));
        lastFailure.remove(companyId);
        return fetched;
    }

    private JWKSet fetch(URI url, String companyId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(url).timeout(fetchTimeout).GET().build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching the JWKS for company " + companyId);
        } catch (IllegalArgumentException malformed) {
            // The JDK client throws this on a response it can't read, such as a Content-Length that isn't
            // a number. The partner chooses that response, so it's a failed fetch like any other.
            throw new IOException("The JWKS for company " + companyId + " answered with a malformed HTTP response");
        }
        try (java.io.InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("The JWKS for company " + companyId + " answered " + response.statusCode());
            }
            byte[] bytes = readWithin(body, companyId, fetchTimeout);
            if (bytes.length > MAX_JWKS_BYTES) {
                throw new IOException("The JWKS for company " + companyId + " is larger than " + MAX_JWKS_BYTES + " bytes");
            }
            return Jwks.parsePublic(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    // The request timeout covers only the wait for the response headers, so the body read is bounded
    // in time here too: a host that trickles bytes can't hold the caller's thread.
    private static byte[] readWithin(java.io.InputStream body, String companyId, Duration timeout) throws IOException {
        java.util.concurrent.CompletableFuture<byte[]> read = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return body.readNBytes(MAX_JWKS_BYTES + 1);
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }, READERS);
        try {
            return read.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException slow) {
            body.close();
            read.cancel(true);
            throw new IOException("The JWKS for company " + companyId + " took longer than " + timeout.toMillis() + " ms");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching the JWKS for company " + companyId);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IOException("Reading the JWKS for company " + companyId + " failed");
        }
    }
}

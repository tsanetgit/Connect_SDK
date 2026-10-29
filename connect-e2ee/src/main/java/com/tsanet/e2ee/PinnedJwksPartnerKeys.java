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
 * {@link PartnerKeys} from a JWKS URL pinned per company in configuration, fetched over HTTPS and
 * cached. A fetch that fails, answers anything but 200, or returns a set with a private key is
 * refused; a cached set is used until it is older than the cache lifetime.
 */
public final class PinnedJwksPartnerKeys implements PartnerKeys {
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_JWKS_BYTES = 64 * 1024;
    private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(1);
    // Virtual threads, so a stalled read never holds a pool thread; it ends when the stream is closed.
    private static final java.util.concurrent.ExecutorService READERS =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, URI> jwksUrls;
    private final Duration cacheLifetime;
    private final HttpClient http;
    private final Clock clock;
    private final Duration fetchTimeout;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(JWKSet keys, Instant fetchedAt) {
    }

    /**
     * @param jwksUrls      each company id's JWKS URL, this member's own included; HTTPS only
     * @param cacheLifetime how long a fetched set is used before it is fetched again. Shorter picks
     *                      up a partner's key rotation sooner
     */
    public PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime) {
        this(jwksUrls, cacheLifetime, HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build(), Clock.systemUTC(),
            FETCH_TIMEOUT);
    }

    PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime, HttpClient http, Clock clock,
                          Duration fetchTimeout) {
        for (URI url : jwksUrls.values()) {
            if (!"https".equalsIgnoreCase(url.getScheme()) && !isLoopback(url)) {
                throw new IllegalArgumentException("A JWKS URL must be HTTPS");
            }
        }
        this.jwksUrls = Map.copyOf(jwksUrls);
        this.cacheLifetime = cacheLifetime;
        this.http = http;
        this.clock = clock;
        this.fetchTimeout = fetchTimeout;
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
     * rest of the cache lifetime, and a sender naming unknown kids can't make every read a fetch.
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
        JWKSet fetched = fetch(url, companyId);
        cache.put(companyId, new Cached(fetched, now));
        return fetched;
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
        JWKSet fetched = fetch(url, companyId);
        cache.put(companyId, new Cached(fetched, now));
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

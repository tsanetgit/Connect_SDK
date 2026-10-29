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

    private final Map<String, URI> jwksUrls;
    private final Duration cacheLifetime;
    private final HttpClient http;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(JWKSet keys, Instant fetchedAt) {
    }

    /**
     * @param jwksUrls      each company id's JWKS URL, this member's own included; HTTPS only
     * @param cacheLifetime how long a fetched set is used before it is fetched again. Shorter picks
     *                      up a partner's key rotation sooner
     */
    public PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime) {
        this(jwksUrls, cacheLifetime, HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build(), Clock.systemUTC());
    }

    PinnedJwksPartnerKeys(Map<String, URI> jwksUrls, Duration cacheLifetime, HttpClient http, Clock clock) {
        for (URI url : jwksUrls.values()) {
            if (!"https".equalsIgnoreCase(url.getScheme()) && !isLoopback(url)) {
                throw new IllegalArgumentException("A JWKS URL must be HTTPS");
            }
        }
        this.jwksUrls = Map.copyOf(jwksUrls);
        this.cacheLifetime = cacheLifetime;
        this.http = http;
        this.clock = clock;
    }

    // Plain HTTP only to this machine, for tests and a local key server; never to another host.
    private static boolean isLoopback(URI url) {
        String host = url.getHost();
        return "http".equalsIgnoreCase(url.getScheme())
            && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host));
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
        HttpRequest request = HttpRequest.newBuilder(url).timeout(FETCH_TIMEOUT).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching the JWKS for company " + companyId);
        }
        if (response.statusCode() != 200) {
            throw new IOException("The JWKS for company " + companyId + " answered " + response.statusCode());
        }
        return Jwks.parsePublic(response.body());
    }
}

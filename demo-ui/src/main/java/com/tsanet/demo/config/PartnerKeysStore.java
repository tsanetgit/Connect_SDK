package com.tsanet.demo.config;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.tsanet.e2ee.Jwks;
import com.tsanet.e2ee.PartnerKeys;
import com.tsanet.e2ee.PinnedJwksPartnerKeys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One environment's partner key table (tsanetgit/Connect_SDK#149): each partner company id maps
 * to either a pinned JWKS URL (HTTPS; fetched and cached by {@link PinnedJwksPartnerKeys}) or a
 * pasted public JWK set (parsed with {@link Jwks#parsePublic}, which refuses private members). The
 * pasted form exists because the pilot partner hardcodes public keys per endpoint instead of
 * publishing a JWKS.
 *
 * <p>Stored as JSON in the data dir next to that environment's credentials, in the same shape the
 * seed variable takes:
 * <pre>{"2087": {"jwksUrl": "https://keys.partner.example/.well-known/jwks.json"},
 *  "3104": {"jwks": {"keys": [ ... public keys only ... ]}}}</pre>
 * On the hosted demo the data dir is ephemeral, so the table is lost on redeploy like the
 * credentials; the seed restores it at startup, and only when the file is absent. A file that
 * exists, even one emptied by removing every entry, wins over the seed.
 *
 * <p>The demo's own company is never in the table: {@link #view} answers it from the demo's own
 * public set, never fetched. Every write rebuilds the pinned fetcher, which copies its URL map.
 */
public class PartnerKeysStore {

    public static final String SOURCE_URL = "url";
    public static final String SOURCE_JWKS = "jwks";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** How long a fetched partner set is used before it is fetched again. */
    private static final Duration CACHE_LIFETIME = Duration.ofHours(1);

    /** One entry: exactly one of {@code jwksUrl} and {@code jwks} is set. */
    public record Entry(URI jwksUrl, JWKSet jwks) {
        public String source() {
            return jwksUrl != null ? SOURCE_URL : SOURCE_JWKS;
        }

        /** The {@code kid}s of a pasted set; empty for a URL entry, whose set is fetched on use. */
        public List<String> kids() {
            return jwks == null ? List.of() : jwks.getKeys().stream().map(JWK::getKeyID).toList();
        }

        @Override
        public String toString() {
            return "Entry[" + source() + (jwksUrl != null ? "=" + jwksUrl : ", " + kids().size() + " keys") + "]";
        }
    }

    /** The resolver over one snapshot of the table; replaced whole on every write. */
    private record Snapshot(Map<String, Entry> entries, PinnedJwksPartnerKeys pinned) {
        static Snapshot of(Map<String, Entry> entries) {
            Map<String, URI> urls = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                if (e.getValue().jwksUrl() != null) {
                    // A URL with no host (https:foo) passes the scheme check but can't be fetched, so it would
                    // otherwise surface only on a later Encrypt. Here it covers the card, the seed and the file.
                    if (e.getValue().jwksUrl().getHost() == null) {
                        throw new IllegalArgumentException("The JWKS URL for company " + e.getKey() + " names no host");
                    }
                    urls.put(e.getKey(), e.getValue().jwksUrl());
                }
            }
            // The constructor is also the URL check: anything but HTTPS (or loopback HTTP) is refused.
            // Insertion order is kept (Map.copyOf would shuffle the listing and the file between runs).
            return new Snapshot(Collections.unmodifiableMap(new LinkedHashMap<>(entries)),
                new PinnedJwksPartnerKeys(urls, CACHE_LIFETIME));
        }
    }

    private final Path path;
    private volatile Snapshot snapshot;

    /**
     * @param path     this environment's table file
     * @param seedJson the seed applied when {@code path} does not exist; null for none
     * @param seedName the variable the seed came from, for the startup message when it is unusable
     */
    public PartnerKeysStore(Path path, String seedJson, String seedName) {
        this.path = Objects.requireNonNull(path);
        // Snapshot.of runs inside each try: it holds the URL scheme check, so a plain-http URL in a
        // seed or a file is reported with the same message as any other unusable entry.
        if (Files.exists(path)) {
            try {
                this.snapshot = Snapshot.of(parse(Files.readString(path)));
            } catch (IOException | IllegalArgumentException unusable) {
                throw new IllegalStateException("The partner key table " + path.getFileName()
                    + " cannot be read: " + unusable.getMessage() + ". Fix or delete the file.");
            }
        } else if (seedJson != null && !seedJson.isBlank()) {
            try {
                this.snapshot = Snapshot.of(parse(seedJson));
            } catch (IOException | IllegalArgumentException unusable) {
                throw new IllegalStateException(seedName + " is not a usable partner key table: "
                    + unusable.getMessage());
            }
        } else {
            this.snapshot = Snapshot.of(new LinkedHashMap<>());
        }
    }

    /** The table, in insertion order. */
    public Map<String, Entry> entries() {
        return snapshot.entries();
    }

    /** Whether {@code companyId} has an entry. The demo's own company never does. */
    public boolean hasEntry(String companyId) {
        return snapshot.entries().containsKey(companyId);
    }

    /**
     * Adds or replaces the URL entry for {@code companyId}.
     *
     * @throws IllegalArgumentException for a URL that is not HTTPS (loopback HTTP is allowed, for a
     *                                  local key server in tests), or a company id that is not a number
     */
    public synchronized void putUrl(String companyId, URI jwksUrl) {
        put(requireCompanyId(companyId), new Entry(Objects.requireNonNull(jwksUrl), null));
    }

    /**
     * Adds or replaces the pasted entry for {@code companyId} with the public keys in {@code jwksJson}.
     *
     * @throws IllegalArgumentException when the text is not a JWK set, holds a private key, or holds no key
     */
    public synchronized void putJwks(String companyId, String jwksJson) {
        requireCompanyId(companyId);
        JWKSet set;
        try {
            set = Jwks.parsePublic(Objects.requireNonNull(jwksJson));
        } catch (IOException refused) {
            throw new IllegalArgumentException(refused.getMessage());
        }
        if (set.getKeys().isEmpty()) {
            throw new IllegalArgumentException("The JWK set holds no keys");
        }
        put(companyId, new Entry(null, set.toPublicJWKSet()));
    }

    private void put(String companyId, Entry entry) {
        Map<String, Entry> next = new LinkedHashMap<>(snapshot.entries());
        next.put(companyId, entry);
        commit(next);
    }

    /** Removes {@code companyId}'s entry; a missing one is not an error. */
    public synchronized void remove(String companyId) {
        if (!snapshot.entries().containsKey(companyId)) {
            return;
        }
        Map<String, Entry> next = new LinkedHashMap<>(snapshot.entries());
        next.remove(companyId);
        commit(next);
    }

    // The new snapshot is built, and so checked, before anything is written or published.
    private void commit(Map<String, Entry> next) {
        Snapshot built = Snapshot.of(next);
        write(next);
        this.snapshot = built;
    }

    /**
     * This environment's {@link PartnerKeys}: the demo's own company from its own public set, a
     * URL entry through the pinned fetcher, a pasted entry as stored. A company with no entry is
     * unavailable, which the profile reports naming the company id.
     */
    public PartnerKeys view(String ownCompanyId, JWKSet ownPublicKeys) {
        Objects.requireNonNull(ownCompanyId);
        Objects.requireNonNull(ownPublicKeys);
        return new PartnerKeys() {
            @Override
            public JWKSet publicKeys(String companyId) throws IOException {
                if (ownCompanyId.equals(companyId)) {
                    return ownPublicKeys;
                }
                Snapshot current = snapshot;
                Entry entry = entry(current, companyId);
                return entry.jwks() != null ? entry.jwks() : current.pinned().publicKeys(companyId);
            }

            @Override
            public JWKSet refreshedPublicKeys(String companyId) throws IOException {
                if (ownCompanyId.equals(companyId)) {
                    return ownPublicKeys;
                }
                Snapshot current = snapshot;
                Entry entry = entry(current, companyId);
                return entry.jwks() != null ? entry.jwks() : current.pinned().refreshedPublicKeys(companyId);
            }

            private Entry entry(Snapshot current, String companyId) throws IOException {
                Entry entry = current.entries().get(companyId);
                if (entry == null) {
                    throw new IOException("No key entry for company " + companyId + " in this environment's partner table");
                }
                return entry;
            }
        };
    }

    private static String requireCompanyId(String companyId) {
        if (companyId == null || !companyId.matches("[0-9]+")) {
            throw new IllegalArgumentException("A partner company id is a number");
        }
        return companyId;
    }

    /** Parses the file or seed shape; refuses an entry with both or neither form, or a private key. */
    static Map<String, Entry> parse(String json) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException notJson) {
            throw new IOException("not JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IOException("the table must be a JSON object keyed by company id");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            String companyId = field.getKey();
            JsonNode node = field.getValue();
            if (!companyId.matches("[0-9]+") || !node.isObject()) {
                throw new IOException("entry " + companyId + " must be an object keyed by a numeric company id");
            }
            JsonNode url = node.get("jwksUrl");
            JsonNode jwks = node.get("jwks");
            if ((url == null) == (jwks == null)) {
                throw new IOException("entry " + companyId + " must have exactly one of jwksUrl and jwks");
            }
            if (url != null) {
                if (!url.isString()) {
                    throw new IOException("entry " + companyId + ": jwksUrl must be a string");
                }
                try {
                    entries.put(companyId, new Entry(URI.create(url.stringValue()), null));
                } catch (IllegalArgumentException bad) {
                    throw new IOException("entry " + companyId + ": jwksUrl is not a URL");
                }
            } else {
                // A pasted set may arrive as the object itself or as its JSON text.
                String text = jwks.isString() ? jwks.stringValue() : jwks.toString();
                JWKSet set = Jwks.parsePublic(text);
                if (set.getKeys().isEmpty()) {
                    throw new IOException("entry " + companyId + ": the JWK set holds no keys");
                }
                entries.put(companyId, new Entry(null, set.toPublicJWKSet()));
            }
        }
        return entries;
    }

    private void write(Map<String, Entry> entries) {
        ObjectNode root = JSON.createObjectNode();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            ObjectNode node = root.putObject(e.getKey());
            if (e.getValue().jwksUrl() != null) {
                node.put("jwksUrl", e.getValue().jwksUrl().toString());
            } else {
                node.set("jwks", JSON.readTree(Jwks.publicJson(e.getValue().jwks())));
            }
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

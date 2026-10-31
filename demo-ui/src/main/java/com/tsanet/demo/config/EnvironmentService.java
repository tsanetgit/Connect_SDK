package com.tsanet.demo.config;

import com.tsanet.api.ConnectApiBaseUrl;
import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.e2ee.E2eeProfile;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Owns the active-environment state and one lazily-created SDK session per
 * environment. Each environment gets an isolated SQLite cache and credentials
 * file under the data dir, so BETA and DEV data never mix. The active
 * environment persists across restarts via a marker file.
 *
 * <p>The SDK takes its auth config at construction time and offers no way to
 * supply credentials afterwards, so a session is a pure function of
 * (environment, credentials). Credentials arrive at runtime from the Settings
 * tab, which means no session can exist before they do, and changing them
 * yields a <em>different</em> session rather than a logged-out one. The cache
 * therefore stores a credentials fingerprint next to each session and rebuilds
 * when it stops matching.
 *
 * <p>The E2EE profile (tsanetgit/Connect_SDK#149) joins that lifecycle: one per environment
 * session, built lazily once the session can answer {@code /v1/me} for the company id, and held in
 * the same holder, so a credentials change or {@link #invalidate} drops it with the session. With
 * E2EE off ({@link E2eeKeys#enabled()} false) no profile is ever built.
 */
@Component
public class EnvironmentService {

    private final DemoProperties properties;
    private final E2eeKeys e2eeKeys;
    private final DemoE2eeProperties e2eeProperties;
    private final Map<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    private final Map<String, CredentialsStore> credentialStores = new ConcurrentHashMap<>();
    private final Map<String, PartnerKeysStore> partnerKeyStores = new ConcurrentHashMap<>();
    private final Path activeEnvFile;
    private volatile String activeEnvironment;

    /** With E2EE off, as a deployment without the key variable runs. */
    public EnvironmentService(DemoProperties properties) {
        this(properties, E2eeKeys.disabled(), new DemoE2eeProperties(null, null));
    }

    @Autowired
    public EnvironmentService(DemoProperties properties, E2eeKeys e2eeKeys, DemoE2eeProperties e2eeProperties) {
        this.e2eeKeys = e2eeKeys;
        this.e2eeProperties = e2eeProperties;
        // Environments are configuration, not user input, so every base URL is checked once,
        // here at startup: a bearer over plain http is a credential on the wire.
        if (properties.environments() != null) {
            for (DemoProperties.EnvironmentDef def : properties.environments().values()) {
                ConnectApiBaseUrl.requireHttps(def.apiBaseUrl(), properties.insecureHttpAllowed(),
                    "tsanet.demo.environments.<env>.api-base-url", "tsanet.demo.allow-insecure-http");
            }
        }
        this.properties = properties;
        this.activeEnvFile = dataDir().resolve("active-environment");
        this.activeEnvironment = loadPersistedEnvironment();
        // With keys loaded, every environment's partner table is read now, so an unusable seed or file
        // fails startup naming its variable instead of failing the Settings page on its first click.
        // Gated on the keys so that, unset, nothing E2EE runs.
        if (e2eeKeys.enabled() && properties.environments() != null) {
            for (String key : properties.environments().keySet()) {
                partnerKeysFor(key);
            }
        }
    }

    private Path dataDir() {
        return Path.of(properties.dataDir());
    }

    private String loadPersistedEnvironment() {
        try {
            if (Files.exists(activeEnvFile)) {
                String persisted = Files.readString(activeEnvFile).trim();
                if (properties.environments().containsKey(persisted)) {
                    return persisted;
                }
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return properties.defaultEnvironment();
    }

    public String activeEnvironment() {
        return activeEnvironment;
    }

    public DemoProperties.EnvironmentDef activeDefinition() {
        return properties.require(activeEnvironment);
    }

    public Map<String, DemoProperties.EnvironmentDef> environments() {
        return properties.environments();
    }

    public synchronized void switchTo(String key) {
        properties.require(key);
        this.activeEnvironment = key;
        try {
            Files.createDirectories(activeEnvFile.getParent());
            Files.writeString(activeEnvFile, key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps stored credentials onto the SDK's auth config for {@code def}.
     *
     * <p>Throws {@link IllegalArgumentException} when the values cannot form a
     * usable config — either because this environment has no Entra tenant and
     * audience, or because the SDK's own record constructors reject them.
     * Callers turn that into a 4xx rather than letting it surface later as a
     * failed request.
     */
    public static AccountAuthConfig toAuthConfig(
        DemoProperties.EnvironmentDef def,
        String mode,
        String principal,
        String secret
    ) {
        if (CredentialsStore.MODE_OAUTH.equals(mode)) {
            if (!def.oauthAvailable()) {
                throw new IllegalArgumentException(
                    def.label() + " has no Entra tenant/audience configured - OAuth mode unavailable");
            }
            // tokenUrl and scope stay null: ClientCredentialsAuthConfig derives
            // the Entra token endpoint from the tenant and the {audience}/.default
            // scope from the audience.
            return new ClientCredentialsAuthConfig(
                def.entraTenantId(), null, principal, secret, def.entraAudience(), null);
        }
        return new PasswordAuthConfig(principal, secret);
    }

    /**
     * {@link #toAuthConfig} for already-stored credentials. Callers use this to
     * reject unusable credentials with a 4xx before building a session, so that
     * failures inside construction stay distinguishable from credential
     * problems.
     */
    public static AccountAuthConfig toAuthConfig(
        DemoProperties.EnvironmentDef def,
        CredentialsStore.Credentials credentials
    ) {
        return toAuthConfig(def, credentials.mode(), credentials.principal(), secretOf(credentials));
    }

    /** The environment definition for {@code key}, for callers that hold a snapshotted key. */
    public DemoProperties.EnvironmentDef definitionFor(String key) {
        return properties.require(key);
    }

    /**
     * Session for {@code key} built from {@code credentials}, reusing the cached
     * one when the credentials are unchanged and rebuilding when they are not. A
     * replaced session is closed once its replacement exists; a caller still
     * holding it sees the requests it already sent finish and any later one fail.
     */
    public TsaNetApiSession sessionFor(String key, CredentialsStore.Credentials credentials) {
        DemoProperties.EnvironmentDef def = properties.require(key);
        // Fingerprinting is hoisted out of the lambda only to keep credential
        // handling off the bin lock. Construction itself deliberately stays
        // inside it: compute() holding the lock across create() is what
        // guarantees one session per key even though create() is the expensive
        // side (directory creation, SQLite open, schema init).
        String fingerprint = fingerprint(credentials);
        return sessions.compute(key, (k, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            SessionHolder replacement = new SessionHolder(create(def, k, credentials), fingerprint);
            if (existing != null) {
                existing.session().close();
            }
            return replacement;
        }).session();
    }

    /**
     * Drops any cached session for {@code key} so the next request rebuilds it.
     * Used when credentials change: the auth mode itself can flip between
     * password and client-credentials, which is a different configuration
     * object rather than a re-login.
     */
    public void invalidate(String key) {
        properties.require(key);
        SessionHolder removed = sessions.remove(key);
        if (removed != null) {
            removed.session().close();
        }
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(holder -> holder.session().close());
        sessions.clear();
    }

    private TsaNetApiSession create(
        DemoProperties.EnvironmentDef def,
        String key,
        CredentialsStore.Credentials credentials
    ) {
        AccountAuthConfig auth = toAuthConfig(def, credentials);
        return TsaNetApi.initialize(new TsaNetApiConfiguration(
            def.apiBaseUrl(),
            dataDir().resolve("data-" + key + ".db").toString(),
            credentials.principal(),
            auth
        ));
    }

    private static String secretOf(CredentialsStore.Credentials credentials) {
        return credentials.isOAuth() ? credentials.clientSecret() : credentials.password();
    }

    /** Hashed so the cache key does not hold a second plaintext copy of the secret. */
    private static String fingerprint(CredentialsStore.Credentials credentials) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {
                credentials.mode(), credentials.principal(), secretOf(credentials)
            }) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint credentials", e);
        }
    }

    /** The profile reference is filled on first E2EE use and dies with the holder. */
    private record SessionHolder(TsaNetApiSession session, String fingerprint, AtomicReference<E2eeProfile> e2ee) {
        SessionHolder(TsaNetApiSession session, String fingerprint) {
            this(session, fingerprint, new AtomicReference<>());
        }
    }

    public E2eeKeys e2eeKeys() {
        return e2eeKeys;
    }

    /**
     * The E2EE profile for {@code session}, or null when E2EE is off. Keyed by the session itself
     * rather than by the active environment, so a concurrent switch can't pair one environment's
     * session with another's partner table. Built on first use from the company id {@code /v1/me}
     * answers, which needs the session authenticated, and then reused.
     *
     * <p>A session that is no longer the one held for any environment (replaced by a credentials
     * change between the caller's two lookups) also answers null: the caller then refuses to
     * encrypt and renders the record unverified, and the next request gets the new session.
     */
    public E2eeProfile e2eeFor(TsaNetApiSession session) {
        if (!e2eeKeys.enabled()) {
            return null;
        }
        for (Map.Entry<String, SessionHolder> entry : sessions.entrySet()) {
            SessionHolder holder = entry.getValue();
            if (holder.session() != session) {
                continue;
            }
            E2eeProfile existing = holder.e2ee().get();
            if (existing != null) {
                return existing;
            }
            Long companyId = session.users().getCurrentUser().companyId();
            if (companyId == null) {
                throw new IllegalStateException("/v1/me answered no company id; the E2EE profile needs one");
            }
            E2eeProfile built = new E2eeProfile(
                companyId.toString(),
                e2eeKeys.keyOperations(),
                partnerKeysFor(entry.getKey()).view(companyId.toString(), e2eeKeys.publicKeySet()),
                e2eeKeys.replayGuard(),
                e2eeKeys.clock());
            // Two first uses may both build; either profile is equivalent, so the first stored wins.
            return holder.e2ee().compareAndSet(null, built) ? built : holder.e2ee().get();
        }
        return null;
    }

    /** Partner key table for environment {@code key}, next to its credentials. */
    public PartnerKeysStore partnerKeysFor(String key) {
        properties.require(key);
        return partnerKeyStores.computeIfAbsent(key,
            k -> new PartnerKeysStore(dataDir().resolve("e2ee-partners-" + k + ".json"),
                e2eeProperties.partnerSeed(k), DemoE2eeProperties.partnerSeedVariable(k)));
    }

    /** Credentials store for the active environment. */
    public CredentialsStore currentCredentials() {
        return credentialsFor(activeEnvironment);
    }

    public CredentialsStore credentialsFor(String key) {
        properties.require(key);
        return credentialStores.computeIfAbsent(key,
            k -> new CredentialsStore(dataDir().resolve("credentials-" + k + ".properties")));
    }
}

package com.tsanet.demo.config;

import com.tsanet.api.TsaNetApi;
import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.ClientCredentialsAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
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
 */
@Component
public class EnvironmentService {

    private final DemoProperties properties;
    private final Map<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    private final Map<String, CredentialsStore> credentialStores = new ConcurrentHashMap<>();
    private final Path activeEnvFile;
    private volatile String activeEnvironment;

    public EnvironmentService(DemoProperties properties) {
        this.properties = properties;
        this.activeEnvFile = dataDir().resolve("active-environment");
        this.activeEnvironment = loadPersistedEnvironment();
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
     * one when the credentials are unchanged and rebuilding when they are not.
     */
    public TsaNetApiSession sessionFor(String key, CredentialsStore.Credentials credentials) {
        DemoProperties.EnvironmentDef def = properties.require(key);
        // Fingerprinting is hoisted out of the lambda only to keep credential
        // handling off the bin lock. Construction itself deliberately stays
        // inside it: compute() holding the lock across create() is what
        // guarantees one session per key even though create() is the expensive
        // side (directory creation, SQLite open, schema init).
        String fingerprint = fingerprint(credentials);
        return sessions.compute(key, (k, existing) ->
            existing != null && existing.fingerprint().equals(fingerprint)
                ? existing
                : new SessionHolder(create(def, k, credentials), fingerprint)
        ).session();
    }

    /**
     * Drops any cached session for {@code key} so the next request rebuilds it.
     * Used when credentials change: the auth mode itself can flip between
     * password and client-credentials, which is a different configuration
     * object rather than a re-login.
     */
    public void invalidate(String key) {
        properties.require(key);
        sessions.remove(key);
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

    private record SessionHolder(TsaNetApiSession session, String fingerprint) {
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

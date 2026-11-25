package com.tsanet.demo.config;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The demo's workspaces (tsanetgit/Connect_SDK#190). A workspace owns one {@link EnvironmentService},
 * so its own active environment, credential stores, SDK sessions, caches and partner key tables, and
 * it names the gate logins that open it. A request is served from the workspace of the login that
 * made it: {@link #current()} is the one place that is decided.
 *
 * <p>The default workspace is built from the configuration the demo has always read: its state lives
 * directly under the data dir, the operator login and the guest login both open it, and its seeds are
 * {@code tsanet.demo.seeds}. With the gate off there are no logins and every request gets it.
 *
 * <p>Each entry of {@code TSANET_DEMO_WORKSPACES} ({@link WorkspaceDefinitions}) adds a workspace with
 * one login of its own, its state under {@code workspaces/<name>} in the data dir, and its own seeds.
 * Names, logins and seeds are all checked before any state is built. A partner seed is checked as its
 * workspace's state is built, and a refusal then closes the state built before it.
 */
@Component
public class Workspaces {

    private static final Logger log = LoggerFactory.getLogger(Workspaces.class);

    /** The workspace the operator and guest logins open, and the one every request gets while the gate is off. */
    public static final String DEFAULT = "default";

    /** A gate login. {@code limitedUploads} holds it to the guest upload limit. */
    public record Login(String username, String password, boolean limitedUploads) {

        /** Never the password: a record's default toString would print it. */
        @Override
        public String toString() {
            return "Login[username=" + username + ", limitedUploads=" + limitedUploads + "]";
        }
    }

    /**
     * One workspace: its state, the logins that open it, and by environment key the credentials
     * {@link CredentialSeeder} writes into a store that is empty. The seeds are checked before the
     * workspace exists, so each one names a configured environment and is complete.
     */
    public record Workspace(String name, EnvironmentService environments, List<Login> logins,
                            Map<String, CredentialsStore.Credentials> seeds) {
    }

    private final List<Workspace> all;
    /** By lower-cased username, as the in-memory user store keys its users. */
    private final Map<String, Workspace> byLogin = new HashMap<>();
    /** What every request gets while the gate is off; null while it is on. */
    private final Workspace open;

    @Autowired
    public Workspaces(DemoProperties properties, E2eeKeys e2eeKeys, DemoE2eeProperties e2eeProperties,
                      DemoAuthProperties auth, DemoWorkspacesProperties configured) {
        this(build(properties, e2eeKeys, e2eeProperties, auth, configured), auth.enabled());
    }

    /** With no workspaces configured: the default one alone. */
    public Workspaces(DemoProperties properties, E2eeKeys e2eeKeys, DemoE2eeProperties e2eeProperties,
                      DemoAuthProperties auth) {
        this(properties, e2eeKeys, e2eeProperties, auth, new DemoWorkspacesProperties(null));
    }

    private static List<Workspace> build(DemoProperties properties, E2eeKeys e2eeKeys, DemoE2eeProperties e2eeProperties,
                                         DemoAuthProperties auth, DemoWorkspacesProperties configured) {
        Map<String, CredentialsStore.Credentials> defaultSeeds = seeds(properties);
        List<WorkspaceDefinitions.Definition> definitions = WorkspaceDefinitions.parse(configured.definitions(), properties);
        if (!definitions.isEmpty() && !auth.enabled()) {
            throw new IllegalStateException(DemoWorkspacesProperties.VARIABLE + " is set but TSANET_DEMO_AUTH_PASSWORD is not:"
                + " the gate would be off while workspace logins are configured. Set the operator password or unset the workspaces.");
        }
        requireDistinctUsernames(auth, definitions);
        Path dataDir = Path.of(properties.dataDir());
        List<Workspace> built = new ArrayList<>();
        try {
            built.add(new Workspace(DEFAULT, new EnvironmentService(properties, e2eeKeys, e2eeProperties, dataDir),
                logins(auth), defaultSeeds));
            for (WorkspaceDefinitions.Definition definition : definitions) {
                built.add(new Workspace(definition.name(),
                    new EnvironmentService(properties, e2eeKeys, partnerSeeds(definition),
                        dataDir.resolve("workspaces").resolve(definition.name())),
                    List.of(definition.login()), definition.seeds()));
            }
        } catch (RuntimeException failed) {
            // A partner seed that cannot be used is found only as its state is built. The context never
            // starts, so nothing else would close what was already built.
            built.forEach(workspace -> workspace.environments().close());
            throw failed;
        }
        return built;
    }

    /**
     * Usernames are compared across every login, without regard to case, as the in-memory user store
     * keys them. The message names the workspaces, never a username: a value entered in the wrong
     * field could be a password.
     */
    private static void requireDistinctUsernames(DemoAuthProperties auth, List<WorkspaceDefinitions.Definition> definitions) {
        Map<String, String> owners = new HashMap<>();
        if (auth.enabled()) {
            owners.put(auth.effectiveUsername().toLowerCase(Locale.ROOT), "the operator login (TSANET_DEMO_AUTH_USER)");
        }
        if (auth.guestEnabled()) {
            owners.put(auth.effectiveGuestUsername().toLowerCase(Locale.ROOT), "the guest login (TSANET_DEMO_AUTH_GUEST_USER)");
        }
        for (WorkspaceDefinitions.Definition definition : definitions) {
            String earlier = owners.putIfAbsent(definition.login().username().toLowerCase(Locale.ROOT),
                "workspace " + definition.name());
            if (earlier != null) {
                throw WorkspaceDefinitions.refuse("gives workspace " + definition.name() + " the same username as "
                    + earlier + " (usernames are compared without regard to case)");
            }
        }
    }

    /** A workspace's partner seeds, which say where they came from when one cannot be used. */
    private static PartnerSeeds partnerSeeds(WorkspaceDefinitions.Definition definition) {
        return new PartnerSeeds() {
            @Override
            public String partnerSeed(String key) {
                return definition.partnerSeeds().get(key);
            }

            @Override
            public String partnerSeedSource(String key) {
                return DemoWorkspacesProperties.VARIABLE + " (workspace " + definition.name() + ", e2eePartners for " + key + ")";
            }
        };
    }

    /**
     * @param all   every workspace; the first is the one every request gets while the gate is off
     * @param gated whether the gate is on
     */
    Workspaces(List<Workspace> all, boolean gated) {
        this.all = List.copyOf(all);
        for (Workspace workspace : this.all) {
            for (Login login : workspace.logins()) {
                // Two logins under one name would leave the second one's requests in the first one's workspace.
                if (byLogin.putIfAbsent(login.username().toLowerCase(Locale.ROOT), workspace) != null) {
                    throw new IllegalStateException("Two gate logins share a username (compared without regard to case).");
                }
            }
        }
        this.open = gated ? null : this.all.get(0);
    }

    /** One ungated workspace over {@code environments}, for a caller that holds a single set of state. */
    public static Workspaces single(EnvironmentService environments, Map<String, CredentialsStore.Credentials> seeds) {
        return new Workspaces(List.of(new Workspace(DEFAULT, environments, List.of(), seeds)), false);
    }

    /**
     * The default workspace's seeds, from {@code tsanet.demo.seeds}. The shipped yml binds every
     * environment's seed, blank when unset, so blank is no seed. An incomplete one is a
     * misconfiguration worth a warning, never a seed. A seed for an environment that does not exist is
     * configuration, not user input: it is refused at startup like the https check is.
     */
    public static Map<String, CredentialsStore.Credentials> seeds(DemoProperties properties) {
        Map<String, CredentialsStore.Credentials> seeds = new LinkedHashMap<>();
        for (Map.Entry<String, DemoProperties.Seed> entry : properties.seedsOrEmpty().entrySet()) {
            String key = entry.getKey();
            DemoProperties.Seed seed = entry.getValue();
            if (seed == null || (!seed.present() && !seed.partial())) {
                continue;
            }
            if (!properties.environments().containsKey(key)) {
                throw new IllegalStateException("tsanet.demo.seeds." + key + " names no configured environment");
            }
            if (seed.partial()) {
                log.warn("Seed for environment {} is incomplete (username or password blank): ignored", key);
                continue;
            }
            seeds.put(key, CredentialsStore.Credentials.password(seed.username(), seed.password()));
        }
        return seeds;
    }

    private static List<Login> logins(DemoAuthProperties auth) {
        List<Login> logins = new ArrayList<>();
        if (auth.enabled()) {
            logins.add(new Login(auth.effectiveUsername(), auth.password(), false));
        }
        if (auth.guestEnabled()) {
            logins.add(new Login(auth.effectiveGuestUsername(), auth.guest().password(), true));
        }
        return logins;
    }

    /** Every workspace, the default one first. */
    public List<Workspace> all() {
        return all;
    }

    /**
     * The workspace of the login that made this request. It reads the login from the request's thread,
     * so a caller that hands work to another thread resolves the workspace first.
     *
     * <p>With the gate on, a request without a login that opens a workspace fails here with an
     * {@link IllegalStateException}; it is never given the default one. An anonymous request is told
     * apart by its type, not its name, so a login that happens to be named like the anonymous user
     * opens nothing for it.
     */
    public Workspace current() {
        if (open != null) {
            return open;
        }
        Authentication login = SecurityContextHolder.getContext().getAuthentication();
        Workspace workspace = login == null || login instanceof AnonymousAuthenticationToken
            ? null
            : byLogin.get(login.getName().toLowerCase(Locale.ROOT));
        if (workspace == null) {
            throw new IllegalStateException("The request carries no login that opens a workspace");
        }
        return workspace;
    }

    @PreDestroy
    public void close() {
        all.forEach(workspace -> workspace.environments().close());
    }
}

package com.tsanet.demo.config;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Writes each workspace's seeds ({@link Workspaces.Workspace#seeds()}) into its environments'
 * credential stores at startup, when a store is empty. Runs during singleton initialization, before the server
 * accepts requests, so a hosted demo on an ephemeral filesystem never answers a request signed in
 * as nobody (tsanetgit/Connect_SDK#152). A seed never overwrites what the store holds, which on a
 * persisted data dir means rotation needs the store cleared first. Writes go to the store
 * directly, so {@code credentials-locked}, which gates the Settings endpoints only, does not apply.
 * A store that cannot be written (an unwritable data dir) surfaces as the store's
 * {@code UncheckedIOException} out of this hook, so the context fails to start, like a seed for an
 * environment that does not exist: a demo that cannot keep its seed is not running as configured.
 * Values are stored as given, like a credential saved through Settings. Nothing here logs a password.
 */
@Component
public class CredentialSeeder {

    private static final Logger log = LoggerFactory.getLogger(CredentialSeeder.class);

    private final DemoProperties properties;
    private final Workspaces workspaces;

    @Autowired
    public CredentialSeeder(DemoProperties properties, Workspaces workspaces) {
        this.properties = properties;
        this.workspaces = workspaces;
    }

    /** Over one set of state, seeded from {@code tsanet.demo.seeds}. */
    public CredentialSeeder(DemoProperties properties, EnvironmentService environments) {
        this(properties, Workspaces.single(environments, Workspaces.seeds(properties)));
    }

    @PostConstruct
    public void seed() {
        workspaces.all().forEach(this::seed);
    }

    private void seed(Workspaces.Workspace workspace) {
        EnvironmentService environments = workspace.environments();
        for (Map.Entry<String, CredentialsStore.Credentials> entry : workspace.seeds().entrySet()) {
            String key = entry.getKey();
            CredentialsStore.Credentials seed = entry.getValue();
            CredentialsStore store = environments.credentialsFor(key);
            if (store.load().isPresent()) {
                log.info("Environment {} already holds credentials: seed not applied", key);
                continue;
            }
            store.save(seed.username(), seed.password());
            log.info("Seeded environment {} with credentials for {}", key, seed.username());
        }
        if (properties.credentialChangesLocked()) {
            // Visitors can switch environments even when locked, so every configured environment
            // with an empty store is a place a visitor can land signed in as nobody.
            for (String key : properties.environments().keySet()) {
                if (environments.credentialsFor(key).load().isEmpty()) {
                    log.warn("Credentials are locked but environment {} holds none: a visitor switching to it sees a demo signed in as nobody", key);
                }
            }
        }
    }
}

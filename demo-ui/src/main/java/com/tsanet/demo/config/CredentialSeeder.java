package com.tsanet.demo.config;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes each configured {@link DemoProperties.Seed} into its environment's credential store at
 * startup, when that store is empty. Runs during singleton initialization, before the server
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
    private final EnvironmentService environments;

    public CredentialSeeder(DemoProperties properties, EnvironmentService environments) {
        this.properties = properties;
        this.environments = environments;
    }

    @PostConstruct
    public void seed() {
        Map<String, DemoProperties.EnvironmentDef> envs = properties.environments();
        for (Map.Entry<String, DemoProperties.Seed> entry : properties.seedsOrEmpty().entrySet()) {
            String key = entry.getKey();
            DemoProperties.Seed seed = entry.getValue();
            if (seed == null || (!seed.present() && !seed.partial())) {
                // The shipped yml binds every environment's seed, blank when unset; blank is no seed.
                continue;
            }
            if (!envs.containsKey(key)) {
                // A value for an environment that does not exist is configuration, not user input:
                // refuse at startup like the https check does.
                throw new IllegalStateException("tsanet.demo.seeds." + key + " names no configured environment");
            }
            if (seed.partial()) {
                log.warn("Seed for environment {} is incomplete (username or password blank): ignored", key);
                continue;
            }
            CredentialsStore store = environments.credentialsFor(key);
            if (store.load().isPresent()) {
                log.info("Environment {} already holds credentials: seed not applied", key);
                continue;
            }
            store.save(seed.username(), seed.password());
            log.info("Seeded environment {} with credentials for {}", key, seed.username());
        }
        if (properties.credentialChangesLocked()) {
            String active = environments.activeEnvironment();
            if (environments.credentialsFor(active).load().isEmpty()) {
                log.warn("Credentials are locked but the active environment {} holds none: visitors will see a demo signed in as nobody", active);
            }
        }
    }
}

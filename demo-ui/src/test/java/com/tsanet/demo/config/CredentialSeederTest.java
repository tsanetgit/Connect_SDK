package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/**
 * Startup seeding against real stores under a temp data dir: an empty store takes the seed, a
 * stored credential is never overwritten, a blank or partial seed does nothing, an unknown
 * environment key fails startup, and the log carries the environment and username but never the
 * password.
 */
@ExtendWith(OutputCaptureExtension.class)
class CredentialSeederTest {

    private static final String SECRET = "s3cret-seed-pw";

    @TempDir
    Path dataDir;

    private EnvironmentService environments;

    private CredentialSeeder seeder(Boolean locked, Map<String, DemoProperties.Seed> seeds) {
        DemoProperties properties = new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // never contacted: nothing here builds a session
            null,
            locked,
            seeds
        );
        environments = new EnvironmentService(properties);
        return new CredentialSeeder(properties, environments);
    }

    private long storedFiles() throws Exception {
        try (Stream<Path> files = Files.walk(dataDir)) {
            return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().contains("credentials")).count();
        }
    }

    @Test
    void seedsAnEmptyStoreInPasswordMode(CapturedOutput output) {
        seeder(null, Map.of("beta", new DemoProperties.Seed("api@demo.example", SECRET))).seed();

        assertThat(environments.credentialsFor("beta").load()).hasValueSatisfying(c -> {
            assertThat(c.mode()).isEqualTo(CredentialsStore.MODE_PASSWORD);
            assertThat(c.username()).isEqualTo("api@demo.example");
            assertThat(c.password()).isEqualTo(SECRET);
        });
        assertThat(environments.credentialsFor("dev").load()).isEmpty();
        assertThat(output.getAll()).contains("Seeded environment beta").contains("api@demo.example").doesNotContain(SECRET);
    }

    @Test
    void neverOverwritesAStoredCredential(CapturedOutput output) {
        CredentialSeeder seeder = seeder(null, Map.of("beta", new DemoProperties.Seed("api@demo.example", SECRET)));
        environments.credentialsFor("beta").save("operator@demo.example", "operator-pw");

        seeder.seed();

        assertThat(environments.credentialsFor("beta").load())
            .hasValueSatisfying(c -> assertThat(c.username()).isEqualTo("operator@demo.example"));
        assertThat(output.getAll()).contains("already holds credentials").doesNotContain(SECRET).doesNotContain("operator-pw");
    }

    @Test
    void blankSeedsFromPlaceholdersDoNothing() throws Exception {
        // A yml ${X:} placeholder binds "" for both values: the shipped default on a local run.
        seeder(null, Map.of("beta", new DemoProperties.Seed("", ""), "dev", new DemoProperties.Seed("", ""))).seed();
        assertThat(storedFiles()).isZero();
    }

    @Test
    void aPartialSeedIsIgnoredWithAWarning(CapturedOutput output) throws Exception {
        seeder(null, Map.of("dev", new DemoProperties.Seed("api@demo.example", ""))).seed();
        assertThat(storedFiles()).isZero();
        assertThat(output.getAll()).contains("WARN").contains("Seed for environment dev is incomplete");
    }

    @Test
    void aSeedForAnUnknownEnvironmentFailsStartup() {
        CredentialSeeder seeder = seeder(null, Map.of("prod", new DemoProperties.Seed("api@demo.example", SECRET)));
        assertThatThrownBy(seeder::seed)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tsanet.demo.seeds.prod")
            .hasMessageNotContaining(SECRET);
    }

    @Test
    void aBlankSeedForAnUnknownEnvironmentIsANoOp() throws Exception {
        // A profile that drops an environment still binds the shipped yml's blank seed for it.
        seeder(null, Map.of("prod", new DemoProperties.Seed("", ""))).seed();
        assertThat(storedFiles()).isZero();
    }

    @Test
    void theContainerRunsTheSeederDuringContextRefresh() {
        // The @PostConstruct path under Spring: binding, EnvironmentService and the seeder wired
        // as beans, with the data dir and a seed given as properties. This proves the hook runs
        // during refresh (singleton initialization); that the embedded server starts only after
        // refresh is Spring Boot's lifecycle (WebServerStartStopLifecycle), not asserted here.
        new ApplicationContextRunner()
            .withUserConfiguration(Bound.class, EnvironmentService.class, CredentialSeeder.class)
            .withPropertyValues(
                "tsanet.demo.environments.beta.label=Beta",
                "tsanet.demo.environments.beta.api-base-url=https://connect.example",
                "tsanet.demo.default-environment=beta",
                "tsanet.demo.data-dir=" + dataDir,
                "tsanet.demo.seeds.beta.username=api@demo.example",
                "tsanet.demo.seeds.beta.password=" + SECRET)
            .run(context -> {
                assertThat(context).hasNotFailed();
                EnvironmentService wired = context.getBean(EnvironmentService.class);
                assertThat(wired.credentialsFor("beta").load())
                    .hasValueSatisfying(c -> assertThat(c.username()).isEqualTo("api@demo.example"));
            });
    }

    @Test
    void lockedWithNothingToSignInAsWarnsForEveryEnvironment(CapturedOutput output) {
        seeder(true, Map.of()).seed();
        assertThat(output.getAll())
            .contains("Credentials are locked but environment beta holds none")
            .contains("Credentials are locked but environment dev holds none");
    }

    @Test
    void lockedAndHalfSeededWarnsForTheUnseededEnvironmentOnly(CapturedOutput output) {
        // The hosted case QA traced: beta seeded, dev not; a visitor can still switch to dev.
        seeder(true, Map.of("beta", new DemoProperties.Seed("api@demo.example", SECRET))).seed();
        assertThat(output.getAll())
            .contains("Credentials are locked but environment dev holds none")
            .doesNotContain("environment beta holds none")
            .doesNotContain(SECRET);
    }

    @Test
    void lockedAndFullySeededDoesNotWarn(CapturedOutput output) {
        seeder(true, Map.of(
            "beta", new DemoProperties.Seed("api@demo.example", SECRET),
            "dev", new DemoProperties.Seed("api@dev.example", SECRET))).seed();
        assertThat(environments.credentialsFor("beta").load()).isPresent();
        assertThat(environments.credentialsFor("dev").load()).isPresent();
        assertThat(output.getAll()).doesNotContain("WARN").doesNotContain(SECRET);
    }

    @Test
    void theShippedYmlWiresTheFourVariablesVerbatim() {
        // Loads the real application.yml (ConfigDataApplicationContextInitializer) with the four
        // environment names set as system properties, which placeholders also read. A typo in a
        // placeholder name would pass every other test and show only on the hosted deploy. Values
        // with ':', '$', '#' and spaces bind verbatim; a value containing "${x:y}" is rewritten by
        // placeholder resolution, which is why the RUNBOOK forbids "${" in a seed.
        new ApplicationContextRunner()
            .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Bound.class)
            .withSystemProperties(
                "TSANET_DEMO_SEED_BETA_USERNAME=api@demo.example",
                "TSANET_DEMO_SEED_BETA_PASSWORD=ab:cd$ef#gh ij",
                "TSANET_DEMO_SEED_DEV_USERNAME=api@dev.example",
                "TSANET_DEMO_SEED_DEV_PASSWORD=ab${cd:zz}ef")
            .run(context -> {
                var seeds = context.getBean(DemoProperties.class).seedsOrEmpty();
                assertThat(seeds.get("beta").username()).isEqualTo("api@demo.example");
                assertThat(seeds.get("beta").password()).isEqualTo("ab:cd$ef#gh ij");
                assertThat(seeds.get("dev").username()).isEqualTo("api@dev.example");
                assertThat(seeds.get("dev").password()).isEqualTo("abzzef");
            });
    }

    @Test
    void seedToStringOmitsThePassword() {
        assertThat(new DemoProperties.Seed("api@demo.example", SECRET).toString())
            .contains("api@demo.example").doesNotContain(SECRET);
    }

    @Configuration
    @EnableConfigurationProperties(DemoProperties.class)
    static class Bound {
    }

    @Test
    void seedsBindFromTheirPropertyPath() {
        new ApplicationContextRunner().withUserConfiguration(Bound.class)
            .withPropertyValues(
                "tsanet.demo.seeds.beta.username=api@demo.example",
                "tsanet.demo.seeds.beta.password=" + SECRET,
                "tsanet.demo.seeds.dev.username=",
                "tsanet.demo.seeds.dev.password=")
            .run(context -> {
                var seeds = context.getBean(DemoProperties.class).seedsOrEmpty();
                assertThat(seeds.get("beta").present()).isTrue();
                assertThat(seeds.get("dev").present()).isFalse();
                assertThat(seeds.get("dev").partial()).isFalse();
            });
        new ApplicationContextRunner().withUserConfiguration(Bound.class)
            .run(context -> assertThat(context.getBean(DemoProperties.class).seedsOrEmpty()).isEmpty());
    }
}

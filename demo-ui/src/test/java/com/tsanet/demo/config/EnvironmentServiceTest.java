package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.TsaNetApiSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline: sessions are built over a temp SQLite dir and never authenticate,
 * so the apiBaseUrl below is never contacted. Sessions expose no close API;
 * as in the library's own session tests, teardown relies on temp-dir deletion.
 */
class EnvironmentServiceTest {

    private static final CredentialsStore.Credentials PW_ONE =
        CredentialsStore.Credentials.password("user@example.com", "pw-one");
    private static final CredentialsStore.Credentials PW_TWO =
        CredentialsStore.Credentials.password("user@example.com", "pw-two");

    @TempDir
    Path dataDir;

    private DemoProperties properties;
    private EnvironmentService service;

    @BeforeEach
    void setUp() {
        properties = new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)
            ),
            "beta",
            dataDir.toString()
        );
        service = new EnvironmentService(properties);
    }

    @Test
    void itReusesTheSessionWhileCredentialsAreUnchanged() {
        TsaNetApiSession first = service.sessionFor("beta", PW_ONE);
        assertThat(service.sessionFor("beta", PW_ONE)).isSameAs(first);
    }

    @Test
    void itRebuildsTheSessionWhenCredentialsChange() {
        TsaNetApiSession first = service.sessionFor("beta", PW_ONE);
        assertThat(service.sessionFor("beta", PW_TWO)).isNotSameAs(first);
    }

    @Test
    void itRebuildsAfterInvalidate() {
        TsaNetApiSession first = service.sessionFor("beta", PW_ONE);
        service.invalidate("beta");
        assertThat(service.sessionFor("beta", PW_ONE)).isNotSameAs(first);
    }

    @Test
    void itKeepsEnvironmentsIsolated() {
        service.credentialsFor("beta").save("user@example.com", "pw-one");
        assertThat(service.credentialsFor("dev").load()).isEmpty();
        assertThat(service.sessionFor("dev", PW_ONE)).isNotSameAs(service.sessionFor("beta", PW_ONE));
    }

    @Test
    void itPersistsTheActiveEnvironmentAcrossInstances() {
        service.switchTo("dev");
        assertThat(new EnvironmentService(properties).activeEnvironment()).isEqualTo("dev");
    }

    @Test
    void itFallsBackToTheDefaultWhenThePersistedEnvironmentIsUnknown() throws Exception {
        Files.writeString(dataDir.resolve("active-environment"), "removed-env");
        assertThat(new EnvironmentService(properties).activeEnvironment()).isEqualTo("beta");
    }
}

package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class SessionGuardTest {

    @TempDir
    Path dataDir;

    private EnvironmentService environments;
    private SessionGuard guard;

    @BeforeEach
    void setUp() {
        environments = new EnvironmentService(new DemoProperties(
            Map.of("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString()
        ));
        guard = new SessionGuard(environments);
    }

    @Test
    void itReturns428WhenNoCredentialsAreStored() {
        assertThatThrownBy(guard::session)
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                assertThat(e.getReason()).contains("credentials not configured");
            });
    }

    @Test
    void itReturns428WhenStoredCredentialsAreUnusableForTheEnvironment() {
        // OAuth credentials stored for an environment with no Entra tenant:
        // loadable, but no usable auth config can be formed from them.
        environments.credentialsFor("beta").saveOAuth("client-id", "client-secret");
        assertThatThrownBy(guard::session)
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                assertThat(e.getReason()).contains("OAuth mode unavailable");
            });
    }
}

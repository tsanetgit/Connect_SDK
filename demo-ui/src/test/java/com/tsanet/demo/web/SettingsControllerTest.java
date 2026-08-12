package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc: no security filter (the Basic gate is env-toggled and
 * out of scope here) and no app context; the store assertions read the real
 * files under the temp data dir, so persistence is checked as the property,
 * not via the response body's claim alone.
 */
class SettingsControllerTest {

    @TempDir
    Path dataDir;

    private EnvironmentService environments;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // One environment, no Entra tenant: OAuth mode is unavailable by config.
        environments = new EnvironmentService(new DemoProperties(
            Map.of("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString()
        ));
        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(environments))
            .setControllerAdvice(new ApiErrorHandler())
            .build();
    }

    @Test
    void itDoesNotPersistCredentialsTheEnvironmentCannotUse() throws Exception {
        mvc.perform(post("/api/settings/beta/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"oauth\",\"clientId\":\"client-id\",\"clientSecret\":\"client-secret\"}"))
            .andExpect(status().isBadRequest());
        assertThat(environments.credentialsFor("beta").load()).isEmpty();
    }

    @Test
    void itRejectsAMissingPasswordBeforeTouchingTheStore() throws Exception {
        mvc.perform(post("/api/settings/beta/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user@example.com\"}"))
            .andExpect(status().isBadRequest());
        assertThat(environments.credentialsFor("beta").load()).isEmpty();
    }

    @Test
    void itSavesValidCredentialsAndTheStoreHoldsThem() throws Exception {
        mvc.perform(post("/api/settings/beta/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user@example.com\",\"password\":\"pw\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environments[0].configured").value(true));
        assertThat(environments.credentialsFor("beta").load())
            .hasValueSatisfying(c -> assertThat(c.principal()).isEqualTo("user@example.com"));
    }

    @Test
    void itRejectsAnUnknownEnvironment() throws Exception {
        mvc.perform(post("/api/settings/removed-env/credentials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user@example.com\",\"password\":\"pw\"}"))
            .andExpect(status().isBadRequest());
    }
}

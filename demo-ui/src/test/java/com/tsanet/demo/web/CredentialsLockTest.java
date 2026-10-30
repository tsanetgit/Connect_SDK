package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.facade.AttachmentStorageFacade;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.EnvironmentService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code tsanet.demo.credentials-locked}: the save and clear endpoints refuse before looking at
 * the body or the environment, the status carries the flag, and everything else on the page
 * (switching, receiver storage) keeps working. Standalone MockMvc, no security filter; the store
 * assertions read the real file under the temp data dir.
 */
class CredentialsLockTest {

    private static final String VALID = "{\"username\":\"user@example.com\",\"password\":\"pw\"}";

    @TempDir
    Path dataDir;

    private EnvironmentService environments;
    private final SessionGuard guard = mock(SessionGuard.class);

    private MockMvc mvc(Boolean locked) {
        DemoProperties properties = new DemoProperties(
            Map.of(
                "beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null),
                "dev", new DemoProperties.EnvironmentDef("Dev", "http://localhost:9", null, null)),
            "beta",
            dataDir.toString(),
            true,  // never contacted: the session is a mock
            null,
            locked,
            null  // no seeds
        );
        environments = new EnvironmentService(properties);
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        AttachmentStorageFacade storage = mock(AttachmentStorageFacade.class);
        when(storage.get()).thenReturn(Optional.empty());
        when(session.attachmentStorage()).thenReturn(storage);
        when(guard.session("beta")).thenReturn(session);
        return MockMvcBuilders.standaloneSetup(new SettingsController(environments, guard, properties))
            .setControllerAdvice(new ApiErrorHandler())
            .build();
    }

    /** The one credentials file the store wrote under the data dir, with its bytes. */
    private Optional<Map.Entry<Path, byte[]>> storedFile() throws IOException {
        try (Stream<Path> files = Files.walk(dataDir)) {
            List<Path> found = files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().contains("credentials")).toList();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Map.entry(found.get(0), Files.readAllBytes(found.get(0))));
        }
    }

    @Test
    void lockedRefusesAValidSaveBeforeTouchingTheStore() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(post("/api/settings/beta/credentials").contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("credentials-locked")));
        assertThat(environments.credentialsFor("beta").load()).isEmpty();
        assertThat(storedFile()).isEmpty();
    }

    @Test
    void lockedRefusesAnEmptyBodyTheSameWay() throws Exception {
        // The lock is checked before validation, so the answer does not depend on the payload.
        mvc(true).perform(post("/api/settings/beta/credentials").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aBodySpringCannotBindFailsBeforeTheGuardAndTouchesNothing() throws Exception {
        // Documents the boundary the guard javadoc states: binding runs first, so malformed JSON
        // is answered by message conversion and ApiErrorHandler, not by the lock's 403; either way
        // the store is untouched.
        mvc(true).perform(post("/api/settings/beta/credentials").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().is(org.hamcrest.Matchers.not(403)))
            .andExpect(status().is(org.hamcrest.Matchers.greaterThanOrEqualTo(400)));
        assertThat(storedFile()).isEmpty();
    }

    @Test
    void lockedRefusesAnUnknownEnvironmentTheSameWay() throws Exception {
        mvc(true).perform(post("/api/settings/removed-env/credentials").contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isForbidden());
    }

    @Test
    void lockedRefusesClearOfAnUnknownEnvironmentTheSameWay() throws Exception {
        mvc(true).perform(delete("/api/settings/removed-env/credentials")).andExpect(status().isForbidden());
    }

    @Test
    void lockedRefusesClearAndLeavesTheStoredFileByteIdentical() throws Exception {
        MockMvc mvc = mvc(true);
        environments.credentialsFor("beta").save("user@example.com", "pw");
        // Pins the lookup storedFile() relies on: the store's file name carries "credentials".
        var before = storedFile().orElseThrow();
        assertThat(before.getKey().getFileName().toString()).contains("credentials");

        mvc.perform(delete("/api/settings/beta/credentials"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("credentials-locked")));

        var after = storedFile().orElseThrow();
        assertThat(after.getKey()).isEqualTo(before.getKey());
        assertThat(after.getValue()).isEqualTo(before.getValue());
        assertThat(environments.credentialsFor("beta").load())
            .hasValueSatisfying(c -> assertThat(c.principal()).isEqualTo("user@example.com"));
    }

    @Test
    void statusCarriesTheLockForTrueFalseAndNull() throws Exception {
        mvc(true).perform(get("/api/settings")).andExpect(jsonPath("$.credentialsLocked").value(true));
        mvc(false).perform(get("/api/settings")).andExpect(jsonPath("$.credentialsLocked").value(false));
        mvc(null).perform(get("/api/settings")).andExpect(jsonPath("$.credentialsLocked").value(false));
    }

    @Test
    void lockedStillSwitchesEnvironments() throws Exception {
        mvc(true).perform(post("/api/settings/environment").contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"dev\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeEnvironment").value("dev"))
            .andExpect(jsonPath("$.credentialsLocked").value(true));
    }

    @Test
    void lockedLeavesReceiverStorageReadsAlone() throws Exception {
        mvc(true).perform(get("/api/settings/beta/receiver-storage")).andExpect(status().isOk());
    }

    @Test
    void unlockedStillSavesAndClears() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(post("/api/settings/beta/credentials").contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isOk());
        assertThat(environments.credentialsFor("beta").load()).isPresent();
        mvc.perform(delete("/api/settings/beta/credentials")).andExpect(status().isNoContent());
        assertThat(environments.credentialsFor("beta").load()).isEmpty();
    }
}

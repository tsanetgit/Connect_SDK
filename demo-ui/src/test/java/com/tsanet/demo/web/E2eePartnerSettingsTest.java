package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.demo.E2eeTestVectors;
import com.tsanet.demo.config.DemoE2eeProperties;
import com.tsanet.demo.config.DemoProperties;
import com.tsanet.demo.config.E2eeKeys;
import com.tsanet.demo.config.EnvironmentService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Settings card's E2EE partner table (tsanetgit/Connect_SDK#149): entries are added, replaced
 * and removed per environment through the controller, a pasted set with a private key is refused
 * before anything is written, and with the demo's keys unset the table can't be written at all.
 * The store's file under the temp data dir is the property checked, not the response alone.
 */
class E2eePartnerSettingsTest {

    @TempDir
    Path dataDir;

    private final E2eeTestVectors v = E2eeTestVectors.load();
    private EnvironmentService environments;

    private MockMvc mvc(E2eeKeys keys) {
        return mvc(keys, false, null);
    }

    private MockMvc mvc(E2eeKeys keys, boolean locked, String betaSeed) {
        DemoProperties properties = new DemoProperties(
            Map.of("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null)),
            "beta", dataDir.toString(), true, null, locked, null);
        environments = new EnvironmentService(properties, keys,
            new DemoE2eeProperties(null, betaSeed == null ? Map.of() : Map.of("beta", betaSeed)));
        return MockMvcBuilders.standaloneSetup(new SettingsController(environments, new SessionGuard(environments), properties))
            .setControllerAdvice(new ApiErrorHandler())
            .build();
    }

    private Path file() {
        return dataDir.resolve("e2ee-partners-beta.json");
    }

    private static String json(String key, String value) {
        return JsonMapper.builder().build().writeValueAsString(Map.of(key, value));
    }

    @Test
    void settingsSayWhetherE2eeIsOnAndWhereTheKeysArePublished() throws Exception {
        mvc(E2eeKeys.fromJson(v.memberAPrivateJwks())).perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.enabled").value(true))
            .andExpect(jsonPath("$.e2ee.jwksPath").value("/.well-known/jwks.json"))
            // An empty table is left out of the JSON; the page reads a missing list as empty.
            .andExpect(jsonPath("$.environments[0].e2eePartners").doesNotExist());
        mvc(E2eeKeys.disabled()).perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.e2ee.enabled").value(false))
            .andExpect(jsonPath("$.e2ee.jwksPath").doesNotExist())
            .andExpect(jsonPath("$.environments[0].e2eePartners").doesNotExist());
    }

    @Test
    void anUnusableSeedFailsStartupNamingItsVariable() {
        DemoProperties properties = new DemoProperties(
            Map.of("beta", new DemoProperties.EnvironmentDef("Beta", "http://localhost:9", null, null)),
            "beta", dataDir.toString(), true, null, null, null);
        DemoE2eeProperties badSeed = new DemoE2eeProperties(null, Map.of("beta", "{\"2087\": {}}"));

        // Keys loaded: the table is read at construction, which is startup for the real bean.
        assertThatThrownBy(() -> new EnvironmentService(properties, E2eeKeys.fromJson(v.memberAPrivateJwks()), badSeed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TSANET_DEMO_E2EE_PARTNERS_BETA");
        // Keys unset: the seed is never read, so nothing E2EE runs and startup is as before.
        assertThatCode(() -> new EnvironmentService(properties, E2eeKeys.disabled(), badSeed)).doesNotThrowAnyException();
        // A good seed is in place from the first request on.
        DemoE2eeProperties goodSeed = new DemoE2eeProperties(null,
            Map.of("beta", "{\"2087\": {\"jwksUrl\": \"https://keys.partner.example/jwks.json\"}}"));
        assertThat(new EnvironmentService(properties, E2eeKeys.fromJson(v.memberAPrivateJwks()), goodSeed)
            .partnerKeysFor("beta").hasEntry("2087")).isTrue();
    }

    @Test
    void aPastedPublicSetIsAddedListedAndRemoved() throws Exception {
        MockMvc mvc = mvc(E2eeKeys.fromJson(v.memberAPrivateJwks()));

        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwks", v.publicJwks("memberB"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].companyId").value("2087"))
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].source").value("jwks"))
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].kids[0]").value(v.rsa("memberB_enc").getKeyID()));
        assertThat(environments.partnerKeysFor("beta").hasEntry("2087")).isTrue();
        assertThat(Files.readString(file())).contains("\"2087\"");

        // Replacing with a URL entry keeps one entry for the company.
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "https://keys.partner.example/.well-known/jwks.json")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environments[0].e2eePartners.length()").value(1))
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].source").value("url"))
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].jwksUrl").value("https://keys.partner.example/.well-known/jwks.json"));

        mvc.perform(delete("/api/settings/beta/e2ee-partners/2087")).andExpect(status().isNoContent());
        assertThat(environments.partnerKeysFor("beta").hasEntry("2087")).isFalse();
        mvc.perform(get("/api/settings")).andExpect(jsonPath("$.environments[0].e2eePartners").doesNotExist());
    }

    @Test
    void aPastedSetWithAPrivateKeyIsRefusedAndNothingIsWritten() throws Exception {
        MockMvc mvc = mvc(E2eeKeys.fromJson(v.memberAPrivateJwks()));

        String body = mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwks", v.memberBPrivateJwks())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("private")))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(v.rsa("memberB_enc").getPrivateExponent().toString().substring(0, 16));
        assertThat(environments.partnerKeysFor("beta").hasEntry("2087")).isFalse();
        assertThat(file()).doesNotExist();
    }

    @Test
    void aPlainHttpUrlBothFormsOrNeitherAndANonNumericIdAreRefused() throws Exception {
        MockMvc mvc = mvc(E2eeKeys.fromJson(v.memberAPrivateJwks()));

        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "http://keys.partner.example/jwks.json")))
            .andExpect(status().isBadRequest());
        // Loopback HTTP, which the module allows for a local key server in tests, is refused on the card:
        // a gate login must not make the hosted task fetch its own ports.
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "http://127.0.0.1:8090/healthz")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("https")));
        // Https with no host is refused at save, not left to fail on a later Encrypt.
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "https:keys.partner.example")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("must have a host")));
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jwksUrl\":\"https://keys.partner.example/jwks.json\",\"jwks\":\"{}\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/beta/e2ee-partners/acme").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "https://keys.partner.example/jwks.json")))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/removed-env/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwksUrl", "https://keys.partner.example/jwks.json")))
            .andExpect(status().isBadRequest());
        assertThat(file()).doesNotExist();
    }

    /**
     * The credentials lock covers the partner table (Shawn's decision on tsanetgit/Connect_SDK#155): a
     * locked demo lists the seeded table and reports the lock, so the page renders it read-only, and
     * refuses both writes before looking at the body; the seed is the table's only source there.
     */
    @Test
    void aLockedDemoListsTheSeededTableReadOnlyAndRefusesBothWrites() throws Exception {
        String seed = "{\"2087\": {\"jwksUrl\": \"https://keys.partner.example/.well-known/jwks.json\"}}";
        MockMvc mvc = mvc(E2eeKeys.fromJson(v.memberAPrivateJwks()), true, seed);

        mvc.perform(get("/api/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.credentialsLocked").value(true))
            .andExpect(jsonPath("$.e2ee.enabled").value(true))
            .andExpect(jsonPath("$.environments[0].e2eePartners[0].companyId").value("2087"));

        // A well-formed replace, and an unparseable body: both 403 before anything is parsed or written.
        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwks", v.publicJwks("memberB"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("credentials-locked")));
        mvc.perform(put("/api/settings/beta/e2ee-partners/3104").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jwks\":\"not a jwks\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/settings/beta/e2ee-partners/2087")).andExpect(status().isForbidden());

        assertThat(environments.partnerKeysFor("beta").entries().keySet()).containsExactly("2087");
        assertThat(environments.partnerKeysFor("beta").entries().get("2087").source()).isEqualTo("url");
        assertThat(file()).as("no write reached the store").doesNotExist();
    }

    @Test
    void withTheDemosKeysUnsetTheTableCannotBeWritten() throws Exception {
        MockMvc mvc = mvc(E2eeKeys.disabled());

        mvc.perform(put("/api/settings/beta/e2ee-partners/2087").contentType(MediaType.APPLICATION_JSON)
                .content(json("jwks", v.publicJwks("memberB"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("TSANET_DEMO_E2EE_PRIVATE_JWKS")));
        mvc.perform(delete("/api/settings/beta/e2ee-partners/2087")).andExpect(status().isForbidden());
        assertThat(file()).doesNotExist();
    }
}

package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionControllerTest {

    private static GitProperties git(String describe, String sha) {
        Properties entries = new Properties();
        if (describe != null) {
            entries.setProperty("commit.id.describe", describe);
        }
        if (sha != null) {
            entries.setProperty("commit.id.abbrev", sha);
        }
        return new GitProperties(entries);
    }

    private static MockMvc mvc(Map<String, Object> beans) {
        var provider = new StaticListableBeanFactory(beans).getBeanProvider(GitProperties.class);
        return MockMvcBuilders.standaloneSetup(new VersionController(provider)).build();
    }

    @Test
    void theEndpointAnswersTheLabelAndForbidsCaching() throws Exception {
        mvc(Map.of("git", git("v5.0.0-3-g01cde11", "01cde11"))).perform(get("/api/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("v5.0.0-3-g01cde11"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void theEndpointAnswersUnknownWithNoGitProperties() throws Exception {
        mvc(Map.of()).perform(get("/api/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(VersionController.UNKNOWN));
    }

    @Test
    void anExactTagAlsoNamesItsCommit() {
        assertThat(VersionController.label(git("v5.0.0", "47cdb4a"))).isEqualTo("v5.0.0 · 47cdb4a");
    }

    @Test
    void aBuildPastATagReadsAsDescribed() {
        assertThat(VersionController.label(git("v5.0.0-3-g01cde11", "01cde11"))).isEqualTo("v5.0.0-3-g01cde11");
    }

    @Test
    void aCheckoutWithNoTagsDoesNotRepeatTheCommit() {
        assertThat(VersionController.label(git("47cdb4a", "47cdb4a"))).isEqualTo("47cdb4a");
    }

    @Test
    void aDirtyBuildOnATagSaysSo() {
        assertThat(VersionController.label(git("v5.0.0-dirty", "47cdb4a"))).isEqualTo("v5.0.0-dirty · 47cdb4a");
    }

    @Test
    void noDescribeFallsBackToTheCommit() {
        assertThat(VersionController.label(git(null, "47cdb4a"))).isEqualTo("47cdb4a");
    }

    @Test
    void aBuildWhereGitFoundNothingIsUnknown() {
        assertThat(VersionController.label(git("", ""))).isEqualTo(VersionController.UNKNOWN);
    }

    @Test
    void aJarWithNoGitPropertiesIsUnknown() {
        assertThat(VersionController.label(null)).isEqualTo(VersionController.UNKNOWN);
    }
}

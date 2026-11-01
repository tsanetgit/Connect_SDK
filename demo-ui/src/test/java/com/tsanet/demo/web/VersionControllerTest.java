package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.GitProperties;

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

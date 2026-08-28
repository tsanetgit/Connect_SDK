package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliJsonFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void itRejectsUnknownKeysInHttpsConfig() throws Exception {
        // Wrong-but-well-formed: valid JSON whose key is a typo of httpsPort. A
        // default Jackson 3 mapper parses this silently with httpsPort unset;
        // this locks in the FAIL_ON_UNKNOWN_PROPERTIES restoration.
        Path config = tempDir.resolve("https-config.json");
        Files.writeString(config, "{\"domain\":\"example.com\",\"httpsPortt\":443}");

        assertThatThrownBy(() -> CliJsonFiles.readHttpsConfig(config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("httpsPortt");
    }

    @Test
    void itParsesValidHttpsConfig() throws Exception {
        Path config = tempDir.resolve("https-config.json");
        Files.writeString(config, "{\"domain\":\"example.com\",\"password\":\"p\",\"httpsPort\":443}");

        var parsed = CliJsonFiles.readHttpsConfig(config);

        assertThat(parsed.domain()).isEqualTo("example.com");
        assertThat(parsed.password()).isEqualTo("p");
        assertThat(parsed.httpsPort()).isEqualTo(443);
    }

    @Test
    void itAcceptsArbitraryKeysInObjectMapReads() throws Exception {
        Path fields = tempDir.resolve("fields.json");
        Files.writeString(fields, "{\"anything\":1,\"goes\":\"here\"}");

        assertThat(CliJsonFiles.readObjectMap(fields)).containsEntry("goes", "here");
    }
}

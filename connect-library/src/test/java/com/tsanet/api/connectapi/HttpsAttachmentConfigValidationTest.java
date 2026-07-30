package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import org.junit.jupiter.api.Test;

class HttpsAttachmentConfigValidationTest {
    @Test
    void itAcceptsValidHttpsConfig() {
        var config = new NormalizedHttpsAttachmentConfigDto(
            "files.example.com",
            "secret",
            "2026-12-31T23:59:59Z",
            "/uploads",
            443
        );

        assertThat(HttpsAttachmentConfigValidation.validate(config).valid()).isTrue();
    }

    @Test
    void itRejectsInvalidPath() {
        var config = new NormalizedHttpsAttachmentConfigDto(
            "files.example.com",
            "secret",
            "2026-12-31T23:59:59Z",
            "uploads",
            443
        );

        assertThat(HttpsAttachmentConfigValidation.validate(config).message()).contains("path");
    }

    @Test
    void itRejectsInvalidPort() {
        var config = new NormalizedHttpsAttachmentConfigDto(
            "files.example.com",
            "secret",
            "2026-12-31T23:59:59Z",
            "/uploads",
            70000
        );

        assertThat(HttpsAttachmentConfigValidation.validate(config).message()).contains("port");
    }
}

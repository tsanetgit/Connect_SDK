package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentForwardValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void itAcceptsValidForwardPayload() throws Exception {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello");

        assertThat(AttachmentForwardValidation.validate("Diagnostic logs", List.of(file)).valid()).isTrue();
    }

    @Test
    void itRejectsMissingFiles() {
        assertThat(AttachmentForwardValidation.validate("Diagnostic logs", List.of()).message())
            .contains("At least one attachment file");
    }

    @Test
    void itRejectsMissingFilePath() {
        Path missing = tempDir.resolve("missing.txt");
        assertThat(AttachmentForwardValidation.validate("Diagnostic logs", List.of(missing)).message())
            .contains("does not exist");
    }

    @Test
    void itRejectsOversizedDescription() {
        String oversized = "x".repeat(AttachmentForwardValidation.MAX_DESCRIPTION_LENGTH + 1);
        assertThat(AttachmentForwardValidation.validate(oversized, List.of()).message())
            .contains("description");
    }
}

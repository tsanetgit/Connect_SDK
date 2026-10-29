package com.tsanet.api.connectapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CaseNoteValidationTest {
    @Test
    void itAcceptsValidNoteContent() {
        assertThat(CaseNoteValidation.validate("Summary", "Note body").valid()).isTrue();
    }

    @Test
    void itRejectsEmptySummary() {
        assertThat(CaseNoteValidation.validate("  ", "body").message()).contains("summary");
    }

    @Test
    void itRejectsEmptyDescription() {
        assertThat(CaseNoteValidation.validate("Summary", " ").message()).contains("text");
    }

    @Test
    void itRejectsOversizedSummary() {
        String oversized = "x".repeat(CaseNoteValidation.MAX_SUMMARY_LENGTH + 1);
        assertThat(CaseNoteValidation.validate(oversized, "body").message()).contains("summary");
    }

    @Test
    void itRejectsOversizedDescription() {
        String oversized = "x".repeat(CaseNoteValidation.MAX_DESCRIPTION_LENGTH + 1);
        assertThat(CaseNoteValidation.validate("Summary", oversized).message()).contains("text");
    }

    @Test
    void itAcceptsCiphertextOverThePlaintextCapsWhenEncrypted() {
        String summary = "x".repeat(CaseNoteValidation.MAX_SUMMARY_LENGTH + 1);
        String description = "x".repeat(CaseNoteValidation.MAX_DESCRIPTION_LENGTH + 1);

        assertThat(CaseNoteValidation.validate(summary, description, true).valid()).isTrue();
        assertThat(CaseNoteValidation.validate(summary, description, false).valid()).isFalse();
    }

    @Test
    void itStillRejectsEmptyContentWhenEncrypted() {
        assertThat(CaseNoteValidation.validate(" ", "body", true).message()).contains("summary");
        assertThat(CaseNoteValidation.validate("Summary", null, true).message()).contains("text");
    }
}

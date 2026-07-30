package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CollaborationRequestNoteAddExecutorTest {
    @Test
    void itDerivesShortSummaryFromDescription() {
        assertThat(CollaborationRequestNoteAddExecutor.deriveSummary("Short note"))
            .isEqualTo("Short note");
    }

    @Test
    void itTruncatesLongDescriptionForAutoSummary() {
        String longText = "a".repeat(100);
        assertThat(CollaborationRequestNoteAddExecutor.deriveSummary(longText))
            .hasSize(80)
            .endsWith("...");
    }
}

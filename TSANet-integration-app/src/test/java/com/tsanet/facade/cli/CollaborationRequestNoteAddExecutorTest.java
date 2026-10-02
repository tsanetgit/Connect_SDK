package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void noTypeLeavesItToTheServer() {
        assertThat(CollaborationRequestNoteAddExecutor.resolveType(new String[] {"--id", "1"})).isNull();
    }

    @Test
    void eitherUserTypeIsAcceptedInAnyCase() {
        assertThat(CollaborationRequestNoteAddExecutor.resolveType(new String[] {"--type", "user_public"}))
            .isEqualTo("USER_PUBLIC");
        assertThat(CollaborationRequestNoteAddExecutor.resolveType(new String[] {"--type", "USER_PARTNER"}))
            .isEqualTo("USER_PARTNER");
    }

    @Test
    void systemOrAnUnknownTypeIsRefused() {
        assertThatThrownBy(() -> CollaborationRequestNoteAddExecutor.resolveType(new String[] {"--type", "SYSTEM"}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollaborationRequestNoteAddExecutor.resolveType(new String[] {"--type", "PUBLIC"}))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollaborationRequestNotesTimelineTest {
    @Test
    void itSortsNotesChronologically() {
        var notes = List.of(
            new CaseNoteDto(2L, 1L, "tok", null, null, null, "B", "Later", "d2", null, null, "n2", "2026-01-02T00:00:00Z", null),
            new CaseNoteDto(1L, 1L, "tok", null, null, null, "A", "Earlier", "d1", null, null, "n1", "2026-01-01T00:00:00Z", null)
        );

        assertThat(CollaborationRequestNotesTimeline.chronological(notes))
            .extracting(CaseNoteDto::summary)
            .containsExactly("Earlier", "Later");
    }

    @Test
    void itBlocksCanceledAndRejectedRequests() {
        var canceled = new CollaborationRequestStatusDto(
            1L, "CANCELED", "s", "A", 1L, "B", 2L, "tok", null, null
        );
        var rejected = new CollaborationRequestStatusDto(
            2L, "REJECTED", "s", "A", 1L, "B", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestNotesTimeline.validateAccess(canceled).canFetch()).isFalse();
        assertThat(CollaborationRequestNotesTimeline.validateAccess(rejected).canFetch()).isFalse();
    }

    @Test
    void itPermitsOpenAndAcceptedRequests() {
        var open = new CollaborationRequestStatusDto(
            1L, "OPEN", "s", "A", 1L, "B", 2L, "tok", null, null
        );
        var accepted = new CollaborationRequestStatusDto(
            2L, "ACCEPTED", "s", "A", 1L, "B", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestNotesTimeline.validateAccess(open).canFetch()).isTrue();
        assertThat(CollaborationRequestNotesTimeline.validateAccess(accepted).canFetch()).isTrue();
    }

    @Test
    void itBuildsContentPreviewAndAuthorFallback() {
        var note = new CaseNoteDto(
            1L, 1L, "tok", null, "user1", null, null, "Summary", "x".repeat(150), null, null, "n1", null, null
        );

        assertThat(CollaborationRequestNotesTimeline.author(note)).isEqualTo("user1");
        assertThat(CollaborationRequestNotesTimeline.contentPreview(note.description())).hasSize(120).endsWith("...");
    }
}

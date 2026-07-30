package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.junit.jupiter.api.Test;

class CollaborationRequestNoteAddTest {
    @Test
    void itAllowsOpenAcceptedAndResolvedRequests() {
        var open = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var accepted = new CollaborationRequestStatusDto(
            2L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );
        var resolved = new CollaborationRequestStatusDto(
            3L, "RESOLVED", "summary", "Acme", 1L, "Beta", 2L, "tok3", null, null
        );

        assertThat(CollaborationRequestNoteAdd.validateForAdd(open).canAdd()).isTrue();
        assertThat(CollaborationRequestNoteAdd.validateForAdd(accepted).canAdd()).isTrue();
        assertThat(CollaborationRequestNoteAdd.validateForAdd(resolved).canAdd()).isTrue();
    }

    @Test
    void itBlocksClosedCanceledAndRejectedRequests() {
        var closed = new CollaborationRequestStatusDto(
            1L, "CLOSED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var canceled = new CollaborationRequestStatusDto(
            2L, "CANCELED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );
        var rejected = new CollaborationRequestStatusDto(
            3L, "REJECTED", "summary", "Acme", 1L, "Beta", 2L, "tok3", null, null
        );

        assertThat(CollaborationRequestNoteAdd.validateForAdd(closed).message()).contains("closed");
        assertThat(CollaborationRequestNoteAdd.validateForAdd(canceled).message()).contains("canceled");
        assertThat(CollaborationRequestNoteAdd.validateForAdd(rejected).message()).contains("rejected");
    }
}

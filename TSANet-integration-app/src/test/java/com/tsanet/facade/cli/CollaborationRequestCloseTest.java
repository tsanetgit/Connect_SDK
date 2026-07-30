package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.junit.jupiter.api.Test;

class CollaborationRequestCloseTest {
    @Test
    void itAllowsOpenRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        assertThat(CollaborationRequestClose.validateForClose(request).closable()).isTrue();
    }

    @Test
    void itAllowsAcceptedRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        assertThat(CollaborationRequestClose.validateForClose(request).closable()).isTrue();
    }

    @Test
    void itBlocksAlreadyClosedRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "CLOSED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        var result = CollaborationRequestClose.validateForClose(request);

        assertThat(result.closable()).isFalse();
        assertThat(result.message()).contains("already closed");
    }

    @Test
    void itBlocksCanceledAndRejectedRequests() {
        var canceled = new CollaborationRequestStatusDto(
            1L, "CANCELED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var rejected = new CollaborationRequestStatusDto(
            2L, "REJECTED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestClose.validateForClose(canceled).message()).contains("canceled");
        assertThat(CollaborationRequestClose.validateForClose(rejected).message()).contains("rejected");
    }
}

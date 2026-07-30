package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.junit.jupiter.api.Test;

class CollaborationRequestInformationRequestTest {
    @Test
    void itAllowsOpenAndAcceptedStatuses() {
        var open = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var accepted = new CollaborationRequestStatusDto(
            2L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestInformationRequest.validateForInformationRequest(open).allowed()).isTrue();
        assertThat(CollaborationRequestInformationRequest.validateForInformationRequest(accepted).allowed()).isTrue();
    }

    @Test
    void itBlocksInformationStatus() {
        var request = new CollaborationRequestStatusDto(
            1L, "INFORMATION", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        var result = CollaborationRequestInformationRequest.validateForInformationRequest(request);

        assertThat(result.allowed()).isFalse();
        assertThat(result.message()).contains("awaiting an information response");
    }

    @Test
    void itBlocksTerminalStatuses() {
        var rejected = new CollaborationRequestStatusDto(
            1L, "REJECTED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var closed = new CollaborationRequestStatusDto(
            2L, "CLOSED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestInformationRequest.validateForInformationRequest(rejected).message())
            .contains("Rejected");
        assertThat(CollaborationRequestInformationRequest.validateForInformationRequest(closed).message())
            .contains("Closed");
    }
}

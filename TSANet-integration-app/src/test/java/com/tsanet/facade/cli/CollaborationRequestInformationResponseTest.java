package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import org.junit.jupiter.api.Test;

class CollaborationRequestInformationResponseTest {
    @Test
    void itAllowsInformationStatus() {
        var request = new CollaborationRequestStatusDto(
            1L, "INFORMATION", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        assertThat(CollaborationRequestInformationResponse.validateForInformationResponse(request).allowed())
            .isTrue();
    }

    @Test
    void itBlocksNonInformationStatuses() {
        var open = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var accepted = new CollaborationRequestStatusDto(
            2L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestInformationResponse.validateForInformationResponse(open).message())
            .contains("INFORMATION");
        assertThat(CollaborationRequestInformationResponse.validateForInformationResponse(accepted).message())
            .contains("INFORMATION");
    }
}

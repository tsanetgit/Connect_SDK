package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollaborationRequestRejectionTest {
    @Test
    void itAllowsInformationStateRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "INFORMATION", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        assertThat(CollaborationRequestRejection.validateForRejection(request, List.of()).rejectable()).isTrue();
    }

    @Test
    void itBlocksAlreadyRejectedRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "REJECTED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        var result = CollaborationRequestRejection.validateForRejection(request, List.of());

        assertThat(result.rejectable()).isFalse();
        assertThat(result.message()).contains("already rejected");
    }

    @Test
    void itBlocksNonInformationStatuses() {
        var open = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var accepted = new CollaborationRequestStatusDto(
            2L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestRejection.validateForRejection(open, List.of()).message())
            .contains("INFORMATION");
        assertThat(CollaborationRequestRejection.validateForRejection(accepted, List.of()).message())
            .contains("INFORMATION");
    }

    @Test
    void itBlocksWhenRejectionResponseAlreadyExists() {
        var request = new CollaborationRequestStatusDto(
            1L, "INFORMATION", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var responses = List.of(
            new CaseResponseDto(10L, "tok1", "REJECTION", null, "Eng", null, "e@x.com", "reason", null)
        );

        var result = CollaborationRequestRejection.validateForRejection(request, responses);

        assertThat(result.rejectable()).isFalse();
        assertThat(result.message()).contains("already has a rejection response");
    }
}

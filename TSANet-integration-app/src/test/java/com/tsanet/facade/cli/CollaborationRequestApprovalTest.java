package com.tsanet.facade.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollaborationRequestApprovalTest {
    @Test
    void itAllowsOpenRequestWithoutApprovalResponse() {
        var request = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        assertThat(CollaborationRequestApproval.validateForApproval(request, List.of()).approvable()).isTrue();
    }

    @Test
    void itBlocksAlreadyAcceptedRequest() {
        var request = new CollaborationRequestStatusDto(
            1L, "ACCEPTED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );

        var result = CollaborationRequestApproval.validateForApproval(request, List.of());

        assertThat(result.approvable()).isFalse();
        assertThat(result.message()).contains("already approved");
    }

    @Test
    void itBlocksCanceledAndResolvedRequests() {
        var canceled = new CollaborationRequestStatusDto(
            1L, "CANCELED", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var resolved = new CollaborationRequestStatusDto(
            2L, "RESOLVED", "summary", "Acme", 1L, "Beta", 2L, "tok2", null, null
        );

        assertThat(CollaborationRequestApproval.validateForApproval(canceled, List.of()).message())
            .contains("canceled");
        assertThat(CollaborationRequestApproval.validateForApproval(resolved, List.of()).message())
            .contains("resolved");
    }

    @Test
    void itBlocksWhenApprovalResponseAlreadyExists() {
        var request = new CollaborationRequestStatusDto(
            1L, "OPEN", "summary", "Acme", 1L, "Beta", 2L, "tok1", null, null
        );
        var responses = List.of(
            new CaseResponseDto(10L, "tok1", "APPROVAL", "CASE-1", "Eng", null, "e@x.com", "Next", null)
        );

        var result = CollaborationRequestApproval.validateForApproval(request, responses);

        assertThat(result.approvable()).isFalse();
        assertThat(result.message()).contains("already has an approval response");
    }
}

package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.generated.api.CaseResponsesApi;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.model.CaseApprovalDTO;
import com.tsanet.api.generated.model.CaseResponseDTO;
import com.tsanet.api.generated.model.CaseResponseType;
import com.tsanet.api.generated.model.CaseStatus;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.storage.CaseResponseRepository;
import com.tsanet.api.storage.CaseResponseStorageService;
import com.tsanet.api.storage.CollaborationRequestRepository;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiResponsesGatewayTest {
    @Mock
    private CollaborationRequestsApi collaborationRequestsApi;
    @Mock
    private CaseResponsesApi caseResponsesApi;

    private CaseResponseStorageService responseStorageService;
    private CollaborationRequestStorageService collaborationStorageService;
    private ConnectApiResponsesGateway gateway;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("responses-gateway-test");
        responseStorageService = new CaseResponseStorageService(new CaseResponseRepository(jdbcTemplate));
        collaborationStorageService = new CollaborationRequestStorageService(new CollaborationRequestRepository(jdbcTemplate));
        gateway = new ConnectApiResponsesGateway(
            collaborationRequestsApi,
            caseResponsesApi,
            GatewayTestSupport.authenticatedSessionStore(),
            responseStorageService,
            collaborationStorageService
        );
    }

    @Test
    void itReadsResponsesFromCollaborationRequestPayload() {
        CaseResponseDTO apiResponse = new CaseResponseDTO()
            .id(3L)
            .type(CaseResponseType.APPROVAL)
            .caseNumber("CASE-1")
            .engineerName("Engineer")
            .createdAt(OffsetDateTime.parse("2026-01-03T10:00:00Z"));
        CollaborationRequestStatusDTO request = GatewayTestSupport.sampleCollaborationRequest("tok-resp");
        request.setCaseResponses(List.of(apiResponse));
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-resp", false)).thenReturn(request);

        List<CaseResponseDto> responses = gateway.getResponses("tok-resp");

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(3L);
            assertThat(response.caseToken()).isEqualTo("tok-resp");
            assertThat(response.type()).isEqualTo("APPROVAL");
        });
        assertThat(responseStorageService.findByCaseToken("tok-resp")).hasSize(1);
    }

    @Test
    void itApprovesCollaborationRequestAndRefreshesCache() {
        CollaborationRequestStatusDTO approved = GatewayTestSupport.sampleCollaborationRequest("tok-approve");
        approved.setStatus(CaseStatus.ACCEPTED);
        when(caseResponsesApi.approveCollaborationRequest(eq("tok-approve"), any(CaseApprovalDTO.class)))
            .thenReturn(approved);
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-approve", false)).thenReturn(approved);

        CollaborationRequestStatusDto result = gateway.approveCollaborationRequest(
            "tok-approve",
            "CASE-9",
            "Engineer",
            "eng@test.com",
            "+123",
            "Next steps"
        );

        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(collaborationStorageService.findAll()).hasSize(1);

        ArgumentCaptor<CaseApprovalDTO> captor = ArgumentCaptor.forClass(CaseApprovalDTO.class);
        verify(caseResponsesApi).approveCollaborationRequest(eq("tok-approve"), captor.capture());
        assertThat(captor.getValue().getCaseNumber()).isEqualTo("CASE-9");
        assertThat(captor.getValue().getNextSteps()).isEqualTo("Next steps");
    }

    @Test
    void itRejectsInvalidRejectionPayload() {
        assertThatThrownBy(() -> gateway.rejectCollaborationRequest("tok", " ", "a@b.com", null, "reason"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void itClosesCollaborationRequest() {
        CollaborationRequestStatusDTO closed = GatewayTestSupport.sampleCollaborationRequest("tok-close");
        closed.setStatus(CaseStatus.CLOSED);
        when(caseResponsesApi.closeCollaborationRequest("tok-close")).thenReturn(closed);
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-close", false)).thenReturn(closed);

        CollaborationRequestStatusDto result = gateway.closeCollaborationRequest("tok-close");

        assertThat(result.status()).isEqualTo("CLOSED");
        verify(caseResponsesApi).closeCollaborationRequest("tok-close");
    }
}

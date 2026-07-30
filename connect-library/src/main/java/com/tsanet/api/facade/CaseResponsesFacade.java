package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import java.util.List;

public interface CaseResponsesFacade {
    List<CaseResponseDto> listResponsesForRequest(String caseToken);

    List<CaseResponseDto> listResponsesForAllRequests();

    List<CaseResponseDto> listStoredResponses();

    List<CaseResponseDto> listStoredResponsesForRequest(String caseToken);

    CollaborationRequestStatusDto approveRequest(
        String caseToken,
        String caseNumber,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String nextSteps
    );

    CollaborationRequestStatusDto closeRequest(String caseToken);

    CollaborationRequestStatusDto rejectRequest(
        String caseToken,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String reason
    );

    CollaborationRequestStatusDto submitInformationRequest(
        String caseToken,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String requestedInformation
    );

    CollaborationRequestStatusDto submitInformationResponse(String caseToken, String requestedInformation);
}

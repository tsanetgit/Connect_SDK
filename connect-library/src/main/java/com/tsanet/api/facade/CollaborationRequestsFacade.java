package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import java.util.List;
import java.util.Map;

public interface CollaborationRequestsFacade {
    List<CollaborationRequestStatusDto> listRequests();

    List<CollaborationRequestStatusDto> listStoredRequests();

    List<CollaborationRequestStatusDto> listStoredRequestsForCompany(long companyId);

    CollaborationRequestFormDto getCreateForm(long receiverCompanyId);

    CollaborationRequestFormTemplateDto getCreateFormByCompanyId(long receiverCompanyId);

    CollaborationRequestFormTemplateDto getCreateFormByDepartmentId(long departmentId);

    CollaborationRequestFormTemplateDto getCreateFormByDocumentId(long documentId);

    List<CollaborationRequestFormDto> listStoredForms();

    List<CollaborationRequestFormDto> listStoredFormsForReceiver(long receiverCompanyId);

    List<CollaborationRequestFormDto> listStoredFormsForDocument(long documentId);

    /**
     * @param testSubmission whether the case is created test-flagged. There is no
     *     default on purpose: every case that is not test-flagged pages a real
     *     engineer at a real partner, so the choice is made per call, deliberately.
     */
    CollaborationRequestStatusDto createRequest(
        long receiverCompanyId,
        String caseNumber,
        String summary,
        String description,
        boolean testSubmission
    );

    /**
     * @param testSubmission whether the case is created test-flagged. See
     *     {@link #createRequest(long, String, String, String, boolean)}.
     */
    CollaborationRequestStatusDto createRequest(
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        boolean testSubmission
    );

    CollaborationRequestStatusDto fetchRequestByToken(String caseToken);

    void syncAllDetails();

    CommunicationSyncSnapshot syncCommunicationContext();
}

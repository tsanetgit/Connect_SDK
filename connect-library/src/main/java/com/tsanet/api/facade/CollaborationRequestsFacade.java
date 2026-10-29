package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import com.tsanet.api.connectapi.dto.E2eeFields;
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
    default CollaborationRequestStatusDto createRequest(
        long receiverCompanyId,
        String caseNumber,
        String summary,
        String description,
        boolean testSubmission
    ) {
        return createRequest(receiverCompanyId, caseNumber, summary, description, testSubmission, null);
    }

    /**
     * As {@link #createRequest(long, String, String, String, boolean)}, plus the end-to-end
     * encryption fields.
     *
     * @param e2ee the manifest and encrypted flag, sent exactly as given; null sends the defaults
     *     every create sent before ({@code "encrypted": false}, no manifest). When
     *     {@code encrypted} is true, {@code summary} and {@code description} are expected to be
     *     ciphertext already and are sent unchanged.
     */
    CollaborationRequestStatusDto createRequest(
        long receiverCompanyId,
        String caseNumber,
        String summary,
        String description,
        boolean testSubmission,
        E2eeFields e2ee
    );

    /**
     * @param testSubmission whether the case is created test-flagged. See
     *     {@link #createRequest(long, String, String, String, boolean)}.
     */
    default CollaborationRequestStatusDto createRequest(
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        boolean testSubmission
    ) {
        return createRequest(formTemplate, caseNumber, summary, description, customFieldValues, testSubmission, null);
    }

    /**
     * As {@link #createRequest(CollaborationRequestFormTemplateDto, String, String, String, Map, boolean)},
     * plus the end-to-end encryption fields. When {@code e2ee} says encrypted, the custom-field
     * values are sent exactly as given rather than stripped. See
     * {@link #createRequest(long, String, String, String, boolean, E2eeFields)}.
     */
    CollaborationRequestStatusDto createRequest(
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        boolean testSubmission,
        E2eeFields e2ee
    );

    CollaborationRequestStatusDto fetchRequestByToken(String caseToken);

    void syncAllDetails();

    CommunicationSyncSnapshot syncCommunicationContext();
}

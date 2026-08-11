package com.tsanet.api.internal;

import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.CaseResponseDto;
import com.tsanet.api.connectapi.CollaborationRequestFormValidation;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import com.tsanet.api.connectapi.dto.UserContextDto;
import com.tsanet.api.connectapi.dto.NormalizedHttpsAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.WebhookDeliveryPageDto;
import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import com.tsanet.api.connectapi.dto.WebhookInboundResultDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import com.tsanet.api.connectapi.dto.WebhookSubscriptionResponseDto;
import com.tsanet.api.connectapi.internal.ConnectApiAttachmentsGateway;
import com.tsanet.api.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiFormGateway;
import com.tsanet.api.connectapi.internal.FormTemplateMapper;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiPartnersGateway;
import com.tsanet.api.connectapi.internal.ConnectApiResponsesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.ConnectApiUserGateway;
import com.tsanet.api.connectapi.internal.ConnectApiWebhooksGateway;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;
import com.tsanet.api.generated.model.CasePriority;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import com.tsanet.api.storage.AttachmentConfigStorageService;
import com.tsanet.api.storage.AttachmentForwardResultStorageService;
import com.tsanet.api.storage.CaseNoteStorageService;
import com.tsanet.api.storage.CaseResponseStorageService;
import com.tsanet.api.storage.CollaborationRequestFormStorageService;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import com.tsanet.api.storage.PartnerSelectionStorageService;
import com.tsanet.api.storage.UserContextStorageService;
import com.tsanet.api.storage.WebhookInboundEventStorageService;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import com.tsanet.api.webhook.WebhookInboundService;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.connectapi.internal.TokenManager;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DefaultTsaNetApiSession implements TsaNetApiSession, AuthFacade, CollaborationRequestsFacade,
    CaseNotesFacade, CaseResponsesFacade, UserFacade, WebhooksFacade, PartnersFacade, AttachmentsFacade {

    private final TsaNetApiConfiguration configuration;
    private final ConnectApiSessionStore sessionStore;
    private final TokenManager tokenManager;
    private final ConnectApiAuthGateway authGateway;
    private final ConnectApiCollaborationGateway collaborationGateway;
    private final ConnectApiFormGateway formGateway;
    private final ConnectApiNotesGateway notesGateway;
    private final ConnectApiResponsesGateway responsesGateway;
    private final ConnectApiUserGateway userGateway;
    private final ConnectApiWebhooksGateway webhooksGateway;
    private final ConnectApiPartnersGateway partnersGateway;
    private final ConnectApiAttachmentsGateway attachmentsGateway;
    private final CollaborationRequestStorageService collaborationRequestStorageService;
    private final CollaborationRequestFormStorageService collaborationRequestFormStorageService;
    private final CaseNoteStorageService caseNoteStorageService;
    private final CaseResponseStorageService caseResponseStorageService;
    private final UserContextStorageService userContextStorageService;
    private final WebhookSubscriptionStorageService webhookSubscriptionStorageService;
    private final WebhookInboundEventStorageService webhookInboundEventStorageService;
    private final WebhookInboundService webhookInboundService;
    private final PartnerSelectionStorageService partnerSelectionStorageService;
    private final AttachmentConfigStorageService attachmentConfigStorageService;
    private final AttachmentForwardResultStorageService attachmentForwardResultStorageService;

    DefaultTsaNetApiSession(
        TsaNetApiConfiguration configuration,
        ConnectApiSessionStore sessionStore,
        TokenManager tokenManager,
        ConnectApiAuthGateway authGateway,
        ConnectApiCollaborationGateway collaborationGateway,
        ConnectApiFormGateway formGateway,
        ConnectApiNotesGateway notesGateway,
        ConnectApiResponsesGateway responsesGateway,
        ConnectApiUserGateway userGateway,
        ConnectApiWebhooksGateway webhooksGateway,
        ConnectApiPartnersGateway partnersGateway,
        ConnectApiAttachmentsGateway attachmentsGateway,
        CollaborationRequestStorageService collaborationRequestStorageService,
        CollaborationRequestFormStorageService collaborationRequestFormStorageService,
        CaseNoteStorageService caseNoteStorageService,
        CaseResponseStorageService caseResponseStorageService,
        UserContextStorageService userContextStorageService,
        WebhookSubscriptionStorageService webhookSubscriptionStorageService,
        WebhookInboundEventStorageService webhookInboundEventStorageService,
        PartnerSelectionStorageService partnerSelectionStorageService,
        AttachmentConfigStorageService attachmentConfigStorageService,
        AttachmentForwardResultStorageService attachmentForwardResultStorageService
    ) {
        this.configuration = configuration;
        this.sessionStore = sessionStore;
        this.tokenManager = tokenManager;
        this.authGateway = authGateway;
        this.collaborationGateway = collaborationGateway;
        this.formGateway = formGateway;
        this.notesGateway = notesGateway;
        this.responsesGateway = responsesGateway;
        this.userGateway = userGateway;
        this.webhooksGateway = webhooksGateway;
        this.partnersGateway = partnersGateway;
        this.attachmentsGateway = attachmentsGateway;
        this.collaborationRequestStorageService = collaborationRequestStorageService;
        this.collaborationRequestFormStorageService = collaborationRequestFormStorageService;
        this.caseNoteStorageService = caseNoteStorageService;
        this.caseResponseStorageService = caseResponseStorageService;
        this.userContextStorageService = userContextStorageService;
        this.webhookSubscriptionStorageService = webhookSubscriptionStorageService;
        this.webhookInboundEventStorageService = webhookInboundEventStorageService;
        this.webhookInboundService = new WebhookInboundService(
            webhookSubscriptionStorageService,
            webhookInboundEventStorageService,
            collaborationGateway,
            notesGateway,
            this::ensureAuthenticatedForWebhook
        );
        this.partnerSelectionStorageService = partnerSelectionStorageService;
        this.attachmentConfigStorageService = attachmentConfigStorageService;
        this.attachmentForwardResultStorageService = attachmentForwardResultStorageService;
    }

    @Override
    public AuthFacade auth() {
        return this;
    }

    @Override
    public CollaborationRequestsFacade collaborationRequests() {
        return this;
    }

    @Override
    public CaseNotesFacade caseNotes() {
        return this;
    }

    @Override
    public CaseResponsesFacade caseResponses() {
        return this;
    }

    @Override
    public UserFacade users() {
        return this;
    }

    @Override
    public WebhooksFacade webhooks() {
        return this;
    }

    @Override
    public PartnersFacade partners() {
        return this;
    }

    @Override
    public AttachmentsFacade attachments() {
        return this;
    }

    @Override
    public String authenticate() {
        return tokenManager.authenticate();
    }

    @Override
    public String login(String username, String password) {
        if (configuration.auth().mode() != AuthMode.CONNECT1_PASSWORD) {
            throw new IllegalStateException("Password login is not configured for this session. Use authenticate().");
        }
        String token = authGateway.login(username, password);
        sessionStore.savePassword(username, token);
        return token;
    }

    @Override
    public String loginWithConfiguredCredentials() {
        return authenticate();
    }

    @Override
    public boolean isAuthorized() {
        return sessionStore.isAuthorized();
    }

    @Override
    public Optional<String> currentUsername() {
        return sessionStore.getUsername();
    }

    @Override
    public Optional<String> currentAccountId() {
        return sessionStore.getAccountId().or(() -> Optional.of(configuration.accountId()));
    }

    @Override
    public Optional<AuthMode> authMode() {
        return sessionStore.getAuthMode().or(() -> Optional.of(configuration.auth().mode()));
    }

    @Override
    public Optional<Instant> tokenExpiresAt() {
        return tokenManager.tokenExpiresAt();
    }

    @Override
    public Optional<String> currentBearerToken() {
        if (!sessionStore.isAuthorized()) {
            return Optional.empty();
        }
        return sessionStore.getBearerToken();
    }

    @Override
    public void logout() {
        sessionStore.clear();
    }

    @Override
    public List<CollaborationRequestStatusDto> listRequests() {
        return collaborationGateway.getCollaborationRequests();
    }

    @Override
    public List<CollaborationRequestStatusDto> listStoredRequests() {
        return collaborationRequestStorageService.findAll();
    }

    @Override
    public List<CollaborationRequestStatusDto> listStoredRequestsForCompany(long companyId) {
        return collaborationRequestStorageService.findByCompanyId(companyId);
    }

    @Override
    public CollaborationRequestFormDto getCreateForm(long receiverCompanyId) {
        return getCreateFormByCompanyId(receiverCompanyId).toStoredMetadata(receiverCompanyId);
    }

    @Override
    public CollaborationRequestFormTemplateDto getCreateFormByCompanyId(long receiverCompanyId) {
        CollaborationRequestDTO form = formGateway.getFormByCompanyId(receiverCompanyId);
        CollaborationRequestFormTemplateDto template = FormTemplateMapper.toTemplate(form, receiverCompanyId, null);
        collaborationRequestFormStorageService.storeFetched(template.toStoredMetadata(receiverCompanyId));
        return template;
    }

    @Override
    public CollaborationRequestFormTemplateDto getCreateFormByDepartmentId(long departmentId) {
        CollaborationRequestDTO form = formGateway.getFormByDepartmentId(departmentId);
        CollaborationRequestFormTemplateDto template = FormTemplateMapper.toTemplate(form, null, departmentId);
        long storageKey = template.receiverCompanyId() != null ? template.receiverCompanyId() : departmentId;
        collaborationRequestFormStorageService.storeFetched(template.toStoredMetadata(storageKey));
        return template;
    }

    @Override
    public CollaborationRequestFormTemplateDto getCreateFormByDocumentId(long documentId) {
        CollaborationRequestDTO form = formGateway.getFormByDocumentId(documentId);
        CollaborationRequestFormTemplateDto template = FormTemplateMapper.toTemplate(form, null, null);
        collaborationRequestFormStorageService.storeFetched(template.toStoredMetadata(documentId));
        return template;
    }

    @Override
    public List<CollaborationRequestFormDto> listStoredForms() {
        return collaborationRequestFormStorageService.findAll();
    }

    @Override
    public List<CollaborationRequestFormDto> listStoredFormsForReceiver(long receiverCompanyId) {
        return collaborationRequestFormStorageService.findByReceiverCompanyId(receiverCompanyId);
    }

    @Override
    public List<CollaborationRequestFormDto> listStoredFormsForDocument(long documentId) {
        return collaborationRequestFormStorageService.findByDocumentId(documentId);
    }

    @Override
    public CollaborationRequestStatusDto createRequest(
        long receiverCompanyId,
        String caseNumber,
        String summary,
        String description,
        boolean testSubmission
    ) {
        CollaborationRequestFormTemplateDto template = getCreateFormByCompanyId(receiverCompanyId);
        return createRequest(template, caseNumber, summary, description, Collections.emptyMap(), testSubmission);
    }

    @Override
    public CollaborationRequestStatusDto createRequest(
        CollaborationRequestFormTemplateDto formTemplate,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        boolean testSubmission
    ) {
        CollaborationRequestDTO form = formGateway.getFormByDocumentId(formTemplate.documentId());
        long storageCompanyId = formTemplate.receiverCompanyId() != null
            ? formTemplate.receiverCompanyId()
            : formTemplate.departmentId() != null ? formTemplate.departmentId() : formTemplate.documentId();
        return createFromForm(
            form,
            storageCompanyId,
            formTemplate.departmentId(),
            caseNumber,
            summary,
            description,
            customFieldValues,
            testSubmission
        );
    }

    private CollaborationRequestStatusDto createFromForm(
        CollaborationRequestDTO form,
        long storageCompanyId,
        Long departmentId,
        String caseNumber,
        String summary,
        String description,
        Map<Long, String> customFieldValues,
        boolean testSubmission
    ) {
        storeFormMetadata(storageCompanyId, departmentId, form);
        FormTemplateMapper.applyCustomFieldValues(form, customFieldValues);

        CollaborationRequestFormValidation.ValidationResult validation =
            CollaborationRequestFormValidation.validateRequiredApiFields(form.getCustomFields());
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        form.setInternalCaseNumber(caseNumber);
        form.setProblemSummary(summary);
        form.setProblemDescription(description);
        form.setTestSubmission(testSubmission);
        if (form.getPriority() == null) {
            form.setPriority(CasePriority.MEDIUM);
        }
        if (form.getCustomFields() == null) {
            form.setCustomFields(Collections.emptyList());
        }
        if (form.getInternalNotes() == null) {
            form.setInternalNotes(Collections.emptyList());
        }
        return collaborationGateway.createCollaborationRequest(form);
    }

    @Override
    public CollaborationRequestStatusDto fetchRequestByToken(String caseToken) {
        return collaborationGateway.getCollaborationRequestByToken(caseToken);
    }

    @Override
    public void syncAllDetails() {
        syncCommunicationContext();
    }

    @Override
    public CommunicationSyncSnapshot syncCommunicationContext() {
        boolean userContextSynced = false;
        if (auth().isAuthorized()) {
            try {
                users().getCurrentUser();
                userContextSynced = true;
            } catch (RuntimeException ignored) {
            }
        }

        List<CollaborationRequestStatusDto> requests = listRequests();
        List<CaseNoteDto> notes = fetchNotesForRequests(requests);
        List<CaseResponseDto> responses = fetchResponsesForRequests(requests);
        fetchAttachmentConfigsForRequests(requests);

        int webhookSubscriptionCount = 0;
        if (auth().isAuthorized()) {
            try {
                webhookSubscriptionCount = webhooks().listSubscriptions().size();
            } catch (RuntimeException ignored) {
            }
        }

        return new CommunicationSyncSnapshot(
            requests.size(),
            notes.size(),
            responses.size(),
            countAttachmentConfigs(requests),
            webhookSubscriptionCount,
            userContextSynced
        );
    }

    private int countAttachmentConfigs(List<CollaborationRequestStatusDto> requests) {
        int count = 0;
        for (CollaborationRequestStatusDto request : requests) {
            if (request.token() == null || request.token().isBlank()) {
                continue;
            }
            if (!attachmentConfigStorageService.findByCaseToken(request.token()).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public List<CaseNoteDto> listNotesForRequest(String caseToken) {
        return notesGateway.getNotes(caseToken);
    }

    @Override
    public List<CaseNoteDto> listNotesForAllRequests() {
        return fetchNotesForRequests(listRequests());
    }

    @Override
    public List<CaseNoteDto> listStoredNotes() {
        return caseNoteStorageService.findAll();
    }

    @Override
    public List<CaseNoteDto> listStoredNotesForRequest(String caseToken) {
        return caseNoteStorageService.findByCaseToken(caseToken);
    }

    @Override
    public CaseNoteDto createNote(String caseToken, String summary, String description, String priority) {
        return notesGateway.createNote(caseToken, summary, description, priority);
    }

    @Override
    public List<CaseResponseDto> listResponsesForRequest(String caseToken) {
        return responsesGateway.getResponses(caseToken);
    }

    @Override
    public List<CaseResponseDto> listResponsesForAllRequests() {
        return fetchResponsesForRequests(listRequests());
    }

    @Override
    public List<CaseResponseDto> listStoredResponses() {
        return caseResponseStorageService.findAll();
    }

    @Override
    public List<CaseResponseDto> listStoredResponsesForRequest(String caseToken) {
        return caseResponseStorageService.findByCaseToken(caseToken);
    }

    @Override
    public CollaborationRequestStatusDto approveRequest(
        String caseToken,
        String caseNumber,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String nextSteps
    ) {
        return responsesGateway.approveCollaborationRequest(
            caseToken,
            caseNumber,
            engineerName,
            engineerEmail,
            engineerPhone,
            nextSteps
        );
    }

    @Override
    public CollaborationRequestStatusDto closeRequest(String caseToken) {
        return responsesGateway.closeCollaborationRequest(caseToken);
    }

    @Override
    public CollaborationRequestStatusDto rejectRequest(
        String caseToken,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String reason
    ) {
        return responsesGateway.rejectCollaborationRequest(
            caseToken,
            engineerName,
            engineerEmail,
            engineerPhone,
            reason
        );
    }

    @Override
    public CollaborationRequestStatusDto submitInformationRequest(
        String caseToken,
        String engineerName,
        String engineerEmail,
        String engineerPhone,
        String requestedInformation
    ) {
        return responsesGateway.submitInformationRequest(
            caseToken,
            engineerName,
            engineerEmail,
            engineerPhone,
            requestedInformation
        );
    }

    @Override
    public CollaborationRequestStatusDto submitInformationResponse(String caseToken, String requestedInformation) {
        return responsesGateway.submitInformationResponse(caseToken, requestedInformation);
    }

    @Override
    public UserContextDto getCurrentUser() {
        return userGateway.getCurrentUser();
    }

    @Override
    public List<UserContextDto> listStoredUsers() {
        return userContextStorageService.findAll();
    }

    @Override
    public List<WebhookSubscriptionDto> listSubscriptions() {
        return webhooksGateway.listWebhookSubscriptions();
    }

    @Override
    public List<WebhookSubscriptionDto> listStoredSubscriptions() {
        return webhookSubscriptionStorageService.findAll();
    }

    @Override
    public WebhookSubscriptionResponseDto createSubscription(String callbackUrl, List<String> eventTypes) {
        return webhooksGateway.createWebhookSubscription(callbackUrl, eventTypes);
    }

    @Override
    public void deleteSubscription(Long id) {
        webhooksGateway.deleteWebhookSubscription(id);
    }

    @Override
    public WebhookDeliveryPageDto listDeliveries(long subscriptionId, int page, int size) {
        return webhooksGateway.listWebhookDeliveries(subscriptionId, page, size);
    }

    @Override
    public List<WebhookInboundEventDto> listStoredInboundEvents() {
        return webhookInboundEventStorageService.findAll();
    }

    @Override
    public WebhookInboundResultDto receiveInbound(String signatureHeader, String rawBody) {
        return webhookInboundService.receive(signatureHeader, rawBody);
    }

    @Override
    public List<PartnerSelectionDto> searchPartners(String searchTerm) {
        return partnersGateway.searchPartners(searchTerm);
    }

    @Override
    public List<PartnerSelectionDto> searchPartnersSemantic(String query, Integer limit) {
        return partnersGateway.searchPartnersSemantic(query, limit);
    }

    @Override
    public List<PartnerSelectionDto> listStoredPartners() {
        return partnerSelectionStorageService.findAll();
    }

    @Override
    public List<PartnerSelectionDto> listStoredPartnersForSearchTerm(String searchTerm) {
        return partnerSelectionStorageService.findBySearchTerm(searchTerm);
    }

    @Override
    public AttachmentConfigDto getAttachmentConfig(String caseToken) {
        return attachmentsGateway.getAttachmentConfig(caseToken);
    }

    @Override
    public List<AttachmentForwardResultDto> forwardAttachments(String caseToken, String description, List<Path> files) {
        return attachmentsGateway.forwardAttachments(caseToken, description, files);
    }

    @Override
    public NormalizedHttpsAttachmentConfigDto analyzeHttpsAttachmentConfig(
        String caseToken,
        Map<String, Object> requestBody
    ) {
        return attachmentsGateway.analyzeHttpsAttachmentConfig(caseToken, requestBody);
    }

    @Override
    public AttachmentConfigDto updateHttpsAttachmentConfig(
        String caseToken,
        NormalizedHttpsAttachmentConfigDto config
    ) {
        return attachmentsGateway.updateHttpsAttachmentConfig(caseToken, config);
    }

    @Override
    public List<StoredAttachmentConfigDto> listStoredAttachmentConfigs() {
        return attachmentConfigStorageService.findAll();
    }

    @Override
    public List<StoredAttachmentConfigDto> listStoredAttachmentConfigsForRequest(String caseToken) {
        return attachmentConfigStorageService.findByCaseToken(caseToken);
    }

    @Override
    public List<StoredAttachmentForwardResultDto> listStoredForwardResults() {
        return attachmentForwardResultStorageService.findAll();
    }

    @Override
    public List<StoredAttachmentForwardResultDto> listStoredForwardResultsForRequest(String caseToken) {
        return attachmentForwardResultStorageService.findByCaseToken(caseToken);
    }

    private CollaborationRequestFormDto storeFormMetadata(
        long receiverCompanyId,
        Long departmentId,
        CollaborationRequestDTO form
    ) {
        if (form.getDocumentId() == null) {
            throw new IllegalStateException("Form for company " + receiverCompanyId + " has no documentId");
        }
        CollaborationRequestFormTemplateDto template = FormTemplateMapper.toTemplate(form, receiverCompanyId, departmentId);
        CollaborationRequestFormDto dto = template.toStoredMetadata(receiverCompanyId);
        collaborationRequestFormStorageService.storeFetched(dto);
        return dto;
    }

    private List<CaseNoteDto> fetchNotesForRequests(List<CollaborationRequestStatusDto> requests) {
        List<CaseNoteDto> allNotes = new ArrayList<>();
        for (CollaborationRequestStatusDto request : requests) {
            if (request.token() == null || request.token().isBlank()) {
                continue;
            }
            allNotes.addAll(notesGateway.getNotes(request.token()));
        }
        return allNotes;
    }

    private List<CaseResponseDto> fetchResponsesForRequests(List<CollaborationRequestStatusDto> requests) {
        List<CaseResponseDto> allResponses = new ArrayList<>();
        for (CollaborationRequestStatusDto request : requests) {
            if (request.token() == null || request.token().isBlank()) {
                continue;
            }
            allResponses.addAll(responsesGateway.getResponses(request.token()));
        }
        return allResponses;
    }

    private void fetchAttachmentConfigsForRequests(List<CollaborationRequestStatusDto> requests) {
        for (CollaborationRequestStatusDto request : requests) {
            if (request.token() == null || request.token().isBlank()) {
                continue;
            }
            attachmentsGateway.getAttachmentConfig(request.token());
        }
    }

    private void ensureAuthenticatedForWebhook() {
        if (!auth().isAuthorized()) {
            auth().authenticate();
        }
    }
}

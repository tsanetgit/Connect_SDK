package com.tsanet.api.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.TsaNetApiConfiguration;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.connectapi.internal.ConnectApiAttachmentsGateway;
import com.tsanet.api.connectapi.internal.ConnectApiAuthGateway;
import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiFormGateway;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiPartnersGateway;
import com.tsanet.api.connectapi.internal.ConnectApiResponsesGateway;
import com.tsanet.api.connectapi.internal.ConnectApiSessionStore;
import com.tsanet.api.connectapi.internal.ConnectApiUserGateway;
import com.tsanet.api.connectapi.internal.ConnectApiWebhooksGateway;
import com.tsanet.api.connectapi.internal.TokenManager;
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
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The write-side test flag must reach the wire exactly as passed. Asserted on the
 * form handed to the collaboration gateway because that IS the outgoing request
 * body; the read-side name differs (testCase), which is why asserting the request
 * object here is the strongest check available without a live round trip.
 */
class DefaultTsaNetApiSessionCreateRequestTest {

    private ConnectApiCollaborationGateway collaborationGateway;
    private ConnectApiFormGateway formGateway;
    private DefaultTsaNetApiSession session;

    @BeforeEach
    void setUp() {
        collaborationGateway = mock(ConnectApiCollaborationGateway.class);
        formGateway = mock(ConnectApiFormGateway.class);
        session = new DefaultTsaNetApiSession(
            mock(TsaNetApiConfiguration.class),
            mock(ConnectApiSessionStore.class),
            mock(TokenManager.class),
            mock(ConnectApiAuthGateway.class),
            collaborationGateway,
            formGateway,
            mock(ConnectApiNotesGateway.class),
            mock(ConnectApiResponsesGateway.class),
            mock(ConnectApiUserGateway.class),
            mock(ConnectApiWebhooksGateway.class),
            mock(ConnectApiPartnersGateway.class),
            mock(ConnectApiAttachmentsGateway.class),
            mock(CollaborationRequestStorageService.class),
            mock(CollaborationRequestFormStorageService.class),
            mock(CaseNoteStorageService.class),
            mock(CaseResponseStorageService.class),
            mock(UserContextStorageService.class),
            mock(WebhookSubscriptionStorageService.class),
            mock(WebhookInboundEventStorageService.class),
            mock(PartnerSelectionStorageService.class),
            mock(AttachmentConfigStorageService.class),
            mock(AttachmentForwardResultStorageService.class)
        );

        CollaborationRequestDTO form = new CollaborationRequestDTO();
        form.setDocumentId(77L);
        form.setCustomFields(Collections.emptyList());
        when(formGateway.getFormByDocumentId(anyLong())).thenReturn(form);
        when(collaborationGateway.createCollaborationRequest(any())).thenReturn(
            new CollaborationRequestStatusDto(1L, "OPEN", "s", "A", 1L, "B", 2L, "tok", null, null)
        );
    }

    private CollaborationRequestFormTemplateDto template() {
        return new CollaborationRequestFormTemplateDto(77L, 5L, null, Collections.emptyList());
    }

    @Test
    void itSendsFalseWhenAProductionConsumerPassesFalse() {
        session.createRequest(template(), "CASE-1", "s", "d", Map.of(), false);

        assertThat(capturedForm().getTestSubmission()).isFalse();
    }

    @Test
    void itSendsTrueWhenPassedTrue() {
        session.createRequest(template(), "CASE-1", "s", "d", Map.of(), true);

        assertThat(capturedForm().getTestSubmission()).isTrue();
    }

    @Test
    void theDeprecatedOverloadKeepsTheOldAlwaysTestBehavior() {
        session.createRequest(template(), "CASE-1", "s", "d", Map.of());

        assertThat(capturedForm().getTestSubmission()).isTrue();
    }

    private CollaborationRequestDTO capturedForm() {
        ArgumentCaptor<CollaborationRequestDTO> captor = ArgumentCaptor.forClass(CollaborationRequestDTO.class);
        verify(collaborationGateway).createCollaborationRequest(captor.capture());
        return captor.getValue();
    }
}

package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.CaseCustomFieldDto;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.generated.model.CaseCustomFieldStatusDTO;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.model.CollaborationRequestDTO;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.storage.CollaborationRequestRepository;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith({MockitoExtension.class, GatewayTestDatabaseExtension.class})
class ConnectApiCollaborationGatewayTest {
    @Mock
    private CollaborationRequestsApi collaborationRequestsApi;

    private ConnectApiSessionStore sessionStore;
    private CollaborationRequestStorageService storageService;
    private ConnectApiCollaborationGateway gateway;

    @BeforeEach
    void setUp() {
        sessionStore = GatewayTestSupport.authenticatedSessionStore();
        JdbcTemplate jdbcTemplate = GatewayTestSupport.inMemoryJdbc("collab-gateway-test");
        storageService = new CollaborationRequestStorageService(new CollaborationRequestRepository(jdbcTemplate));
        gateway = new ConnectApiCollaborationGateway(collaborationRequestsApi, sessionStore, storageService);
    }

    @Test
    void itRequiresLoginBeforeListingRequests() {
        sessionStore.clear();

        assertThatThrownBy(gateway::getCollaborationRequests)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not logged in");
    }

    @Test
    void itMapsListResponseAndPersistsToSqlite() {
        CollaborationRequestStatusDTO apiDto = GatewayTestSupport.sampleCollaborationRequest("tok-list");
        when(collaborationRequestsApi.listCollaborationRequests(null, null, null, null, null, false))
            .thenReturn(List.of(apiDto));

        List<CollaborationRequestStatusDto> requests = gateway.getCollaborationRequests();

        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.id()).isEqualTo(42L);
            assertThat(request.status()).isEqualTo("OPEN");
            assertThat(request.summary()).isEqualTo("Need help");
            assertThat(request.token()).isEqualTo("tok-list");
        });
        assertThat(storageService.findAll()).hasSize(1);
    }

    @Test
    void itFetchesRequestByToken() {
        CollaborationRequestStatusDTO apiDto = GatewayTestSupport.sampleCollaborationRequest("tok-abc");
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-abc", false)).thenReturn(apiDto);

        CollaborationRequestStatusDto request = gateway.getCollaborationRequestByToken("tok-abc");

        assertThat(request.token()).isEqualTo("tok-abc");
        assertThat(storageService.findAll()).singleElement().extracting(CollaborationRequestStatusDto::token)
            .isEqualTo("tok-abc");
        verify(collaborationRequestsApi).getCollaborationRequestByToken("tok-abc", false);
    }

    @Test
    void itCarriesTheE2eeFieldsFromAReadIntoTheDtoAndTheCacheUnchanged() {
        String manifest = "eyJhbGciOiJFUzI1NiJ9.eyJ2ZXIiOiJ0c2FuZXQtZTJlZS0xIn0.c2lnLV9-";
        CollaborationRequestStatusDTO apiDto = GatewayTestSupport.sampleCollaborationRequest("tok-e2ee")
            .manifest(manifest)
            .encrypted(true);
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-e2ee", false)).thenReturn(apiDto);

        CollaborationRequestStatusDto request = gateway.getCollaborationRequestByToken("tok-e2ee");

        assertThat(request.manifest()).isEqualTo(manifest);
        assertThat(request.encrypted()).isTrue();
        assertThat(storageService.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.manifest()).isEqualTo(manifest);
            assertThat(stored.encrypted()).isTrue();
        });
    }

    @Test
    void itCarriesTheCaseContentFromAReadIntoTheDtoAndTheCacheUnchanged() {
        CollaborationRequestStatusDTO apiDto = GatewayTestSupport.sampleCollaborationRequest("tok-content")
            .submitterCaseNumber("A-2026-004417")
            .description("{\"recipients\":[]}")
            .customFields(new java.util.ArrayList<>(java.util.Arrays.asList(
                new CaseCustomFieldStatusDTO().id(501L).fieldName("Product").value("{\"x\":1}"),
                new CaseCustomFieldStatusDTO().id(502L).fieldName("Blank").value(""),
                new CaseCustomFieldStatusDTO().id(503L).fieldName("None").value(null))));
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-content", false)).thenReturn(apiDto);

        CollaborationRequestStatusDto request = gateway.getCollaborationRequestByToken("tok-content");

        List<CaseCustomFieldDto> expected = java.util.Arrays.asList(
            new CaseCustomFieldDto(501L, "Product", "{\"x\":1}"),
            new CaseCustomFieldDto(502L, "Blank", ""),
            new CaseCustomFieldDto(503L, "None", null));
        assertThat(request.submitterCaseNumber()).isEqualTo("A-2026-004417");
        assertThat(request.description()).isEqualTo("{\"recipients\":[]}");
        assertThat(request.customFields()).containsExactlyElementsOf(expected);
        assertThat(storageService.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.submitterCaseNumber()).isEqualTo("A-2026-004417");
            assertThat(stored.description()).isEqualTo("{\"recipients\":[]}");
            assertThat(stored.customFields()).containsExactlyElementsOf(expected);
        });
    }

    @Test
    void omittedCustomFieldsReadEmptyAndOnlyAnExplicitNullReadsNull() {
        // The generated read model starts customFields as an empty list, so an omitted array reads
        // empty; null comes only from an explicit null (or a row cached before the field existed).
        CollaborationRequestStatusDTO omitted = GatewayTestSupport.sampleCollaborationRequest("tok-omit");
        CollaborationRequestStatusDTO explicitNull = GatewayTestSupport.sampleCollaborationRequest("tok-null")
            .customFields(null);
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-omit", false)).thenReturn(omitted);
        when(collaborationRequestsApi.getCollaborationRequestByToken("tok-null", false)).thenReturn(explicitNull);

        assertThat(gateway.getCollaborationRequestByToken("tok-omit").customFields()).isEmpty();
        assertThat(gateway.getCollaborationRequestByToken("tok-null").customFields()).isNull();
    }

    @Test
    void itCreatesCollaborationRequest() {
        CollaborationRequestDTO createPayload = new CollaborationRequestDTO();
        CollaborationRequestStatusDTO created = GatewayTestSupport.sampleCollaborationRequest("tok-new");
        created.setSummary("Created case");
        when(collaborationRequestsApi.createCollaborationRequest(createPayload)).thenReturn(created);

        CollaborationRequestStatusDto result = gateway.createCollaborationRequest(createPayload);

        assertThat(result.summary()).isEqualTo("Created case");
        assertThat(storageService.findAll()).hasSize(1);
        verify(collaborationRequestsApi).createCollaborationRequest(createPayload);
    }
}

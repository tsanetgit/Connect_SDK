package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
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

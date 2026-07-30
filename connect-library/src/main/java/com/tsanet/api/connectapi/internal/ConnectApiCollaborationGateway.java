package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.model.CollaborationRequestStatusDTO;
import com.tsanet.api.storage.CollaborationRequestStorageService;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

public class ConnectApiCollaborationGateway {
    private final CollaborationRequestsApi collaborationRequestsApi;
    private final ConnectApiSessionStore sessionStore;
    private final CollaborationRequestStorageService storageService;

    public ConnectApiCollaborationGateway(
        CollaborationRequestsApi collaborationRequestsApi,
        ConnectApiSessionStore sessionStore,
        CollaborationRequestStorageService storageService
    ) {
        this.collaborationRequestsApi = collaborationRequestsApi;
        this.sessionStore = sessionStore;
        this.storageService = storageService;
    }

    public List<CollaborationRequestStatusDto> getCollaborationRequests() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));

        List<CollaborationRequestStatusDTO> body = collaborationRequestsApi.listCollaborationRequests(
            null,
            null,
            null,
            null,
            null,
            false
        );
        if (body == null) {
            return Collections.emptyList();
        }
        List<CollaborationRequestStatusDto> requests = body.stream().map(this::toDto).toList();
        storageService.storeFetched(requests);
        return requests;
    }

    public CollaborationRequestStatusDto getCollaborationRequestByToken(String caseToken) {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));

        CollaborationRequestStatusDTO body = collaborationRequestsApi.getCollaborationRequestByToken(caseToken, false);
        if (body == null) {
            throw new IllegalStateException("Collaboration request token=" + caseToken + " not found");
        }
        CollaborationRequestStatusDto request = toDto(body);
        storageService.storeFetched(List.of(request));
        return request;
    }

    public CollaborationRequestStatusDto createCollaborationRequest(
        com.tsanet.api.generated.model.CollaborationRequestDTO request
    ) {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));

        CollaborationRequestStatusDTO body = collaborationRequestsApi.createCollaborationRequest(request);
        if (body == null) {
            throw new IllegalStateException("Create collaboration request returned empty response");
        }
        CollaborationRequestStatusDto created = toDto(body);
        storageService.storeFetched(List.of(created));
        return created;
    }

    private CollaborationRequestStatusDto toDto(CollaborationRequestStatusDTO dto) {
        String status = dto.getStatus() != null ? dto.getStatus().getValue() : null;
        return new CollaborationRequestStatusDto(
            dto.getId(),
            status,
            dto.getSummary(),
            dto.getSubmitCompanyName(),
            dto.getSubmitCompanyId(),
            dto.getReceiveCompanyName(),
            dto.getReceiveCompanyId(),
            dto.getToken(),
            formatDateTime(dto.getCreatedAt()),
            formatDateTime(dto.getUpdatedAt())
        );
    }

    private static String formatDateTime(OffsetDateTime value) {
        return value != null ? value.toString() : null;
    }
}

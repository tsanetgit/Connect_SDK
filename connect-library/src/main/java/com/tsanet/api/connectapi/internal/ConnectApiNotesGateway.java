package com.tsanet.api.connectapi.internal;

import static com.tsanet.api.connectapi.internal.OpenApiMapping.dateTime;
import static com.tsanet.api.connectapi.internal.OpenApiMapping.enumValue;

import com.tsanet.api.connectapi.CaseNoteValidation;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseNoteTemplateDTO;
import com.tsanet.api.generated.model.NotePriority;
import com.tsanet.api.storage.CaseNoteStorageService;
import java.util.Collections;
import java.util.List;

public class ConnectApiNotesGateway {
    private final CaseNotesApi caseNotesApi;
    private final ConnectApiSessionStore sessionStore;
    private final CaseNoteStorageService storageService;

    public ConnectApiNotesGateway(
        CaseNotesApi caseNotesApi,
        ConnectApiSessionStore sessionStore,
        CaseNoteStorageService storageService
    ) {
        this.caseNotesApi = caseNotesApi;
        this.sessionStore = sessionStore;
        this.storageService = storageService;
    }

    public List<CaseNoteDto> getNotes(String caseToken) {
        requireLogin();

        List<CaseNoteDTO> body = caseNotesApi.getNotes(caseToken, null, null, false);
        if (body == null) {
            return Collections.emptyList();
        }
        List<CaseNoteDto> notes = body.stream().map(note -> toDto(note, caseToken)).toList();
        storageService.storeFetched(notes);
        return notes;
    }

    public CaseNoteDto createNote(String caseToken, String summary, String description, String priority) {
        requireLogin();

        CaseNoteValidation.ValidationResult validation = CaseNoteValidation.validate(summary, description);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        CaseNoteTemplateDTO template = new CaseNoteTemplateDTO();
        template.setSummary(summary.strip());
        template.setDescription(description.strip());
        template.setPriority(NotePriority.fromValue(priority));

        CaseNoteDTO created = caseNotesApi.createNote(caseToken, template);
        if (created == null) {
            throw new IllegalStateException("Create note returned empty response");
        }
        CaseNoteDto note = toDto(created, caseToken);
        getNotes(caseToken);
        return note;
    }

    private CaseNoteDto toDto(CaseNoteDTO dto, String caseToken) {
        return new CaseNoteDto(
            dto.getId(),
            dto.getCaseId(),
            caseToken,
            dto.getCompanyName(),
            dto.getCreatorUsername(),
            dto.getCreatorEmail(),
            dto.getCreatorName(),
            dto.getSummary(),
            dto.getDescription(),
            enumValue(dto.getPriority()),
            enumValue(dto.getStatus()),
            dto.getToken(),
            dateTime(dto.getCreatedAt()),
            dateTime(dto.getUpdatedAt())
        );
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}

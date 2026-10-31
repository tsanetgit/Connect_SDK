package com.tsanet.api.connectapi.internal;

import com.tsanet.api.connectapi.CaseNoteValidation;
import com.tsanet.api.connectapi.OpenApiRecords;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.E2eeFields;
import com.tsanet.api.connectapi.dto.NoteTypeDto;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.model.CaseNoteDTO;
import com.tsanet.api.generated.model.CaseNoteTemplateDTO;
import com.tsanet.api.generated.model.NotePriority;
import com.tsanet.api.generated.model.NoteTypeReferenceDTO;
import com.tsanet.api.generated.model.NoteTypeSelectionDTO;
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
        return createNote(caseToken, summary, description, priority, null);
    }

    public List<NoteTypeDto> listNoteTypes() {
        requireLogin();

        List<NoteTypeSelectionDTO> body = caseNotesApi.getNoteTypes();
        if (body == null) {
            return Collections.emptyList();
        }
        return body.stream().map(type -> new NoteTypeDto(type.getCode(), type.getDisplayName())).toList();
    }

    /**
     * The type is passed through as {@code {"code": type}}; the server validates it. A null type
     * sets no reference at all: never a reference with a null code, so the request carries
     * {@code "type": null}, which the server stores as no type.
     */
    public CaseNoteDto createNote(String caseToken, String summary, String description, String priority, String type) {
        return createNote(caseToken, summary, description, priority, type, null);
    }

    /**
     * As {@link #createNote(String, String, String, String, String)}, plus the end-to-end
     * encryption fields. A null {@code e2ee} sends the generated model's defaults, as every create
     * did before: {@code "encrypted": false} and no manifest. Otherwise both are sent exactly as
     * given, and when {@code encrypted} is true the summary and description are sent unstripped
     * and the length caps are skipped (see {@link CaseNoteValidation#validate(String, String, boolean)}).
     */
    public CaseNoteDto createNote(
        String caseToken,
        String summary,
        String description,
        String priority,
        String type,
        E2eeFields e2ee
    ) {
        requireLogin();

        boolean encrypted = e2ee != null && e2ee.encrypted();
        CaseNoteValidation.ValidationResult validation = CaseNoteValidation.validate(summary, description, encrypted);
        if (!validation.valid()) {
            throw new IllegalArgumentException(validation.message());
        }

        // Ciphertext is sent byte for byte: the sender's manifest digests cover the exact value.
        CaseNoteTemplateDTO template = new CaseNoteTemplateDTO();
        template.setSummary(encrypted ? summary : summary.strip());
        template.setDescription(encrypted ? description : description.strip());
        if (priority == null) {
            throw new IllegalArgumentException("A note priority is required");
        }
        template.setPriority(NotePriority.fromValue(priority));
        if (type != null) {
            template.setType(new NoteTypeReferenceDTO().code(type));
        }
        if (e2ee != null) {
            template.setManifest(e2ee.manifest());
            template.setEncrypted(e2ee.encrypted());
        }

        CaseNoteDTO created = caseNotesApi.createNote(caseToken, template);
        if (created == null) {
            throw new IllegalStateException("Create note returned empty response");
        }
        CaseNoteDto note = toDto(created, caseToken);
        getNotes(caseToken);
        return note;
    }

    private CaseNoteDto toDto(CaseNoteDTO dto, String caseToken) {
        return OpenApiRecords.caseNote(dto, caseToken);
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}

package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.E2eeFields;
import com.tsanet.api.connectapi.dto.NoteTypeDto;
import java.util.List;

public interface CaseNotesFacade {
    List<CaseNoteDto> listNotesForRequest(String caseToken);

    List<CaseNoteDto> listNotesForAllRequests();

    List<CaseNoteDto> listStoredNotes();

    List<CaseNoteDto> listStoredNotesForRequest(String caseToken);

    /**
     * The note types a caller may send on create, in the server's display order, from
     * {@code GET /v1/note-types}. The server owns this list and can grow it without an API
     * release, so read it rather than hard-coding codes.
     */
    List<NoteTypeDto> listNoteTypes();

    /** Create a note with no type. The server stores none; there is no default type. */
    default CaseNoteDto createNote(String caseToken, String summary, String description, String priority) {
        return createNote(caseToken, summary, description, priority, null);
    }

    /**
     * Create a note with the given type code, one {@link #listNoteTypes()} returns. The code is
     * sent as given; the server matches it ignoring case and refuses any other with a 400, which
     * reaches the caller as a {@link com.tsanet.api.ConnectApiException}. A null type creates a
     * note with no type.
     */
    default CaseNoteDto createNote(String caseToken, String summary, String description, String priority, String type) {
        return createNote(caseToken, summary, description, priority, type, null);
    }

    /**
     * As {@link #createNote(String, String, String, String, String)}, plus the end-to-end
     * encryption fields.
     *
     * @param e2ee the manifest and encrypted flag, sent exactly as given; null sends the defaults
     *     every create sent before ({@code "encrypted": false}, no manifest). When
     *     {@code encrypted} is true, {@code summary} and {@code description} are expected to be
     *     ciphertext already: they are sent unstripped, and the plaintext length caps don't apply.
     */
    CaseNoteDto createNote(
        String caseToken,
        String summary,
        String description,
        String priority,
        String type,
        E2eeFields e2ee
    );
}

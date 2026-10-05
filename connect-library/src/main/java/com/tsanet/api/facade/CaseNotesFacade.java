package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;

public interface CaseNotesFacade {
    /**
     * The note types a caller may create: every API note type except {@code SYSTEM}, which the
     * platform writes. This is the one copy of that rule; a test keeps it equal to the spec's.
     */
    List<String> CREATABLE_NOTE_TYPES = List.of("USER_PARTNER", "USER_PUBLIC");

    List<CaseNoteDto> listNotesForRequest(String caseToken);

    List<CaseNoteDto> listNotesForAllRequests();

    List<CaseNoteDto> listStoredNotes();

    List<CaseNoteDto> listStoredNotesForRequest(String caseToken);

    /** Create a note with the server's default type, {@code USER_PARTNER}. */
    default CaseNoteDto createNote(String caseToken, String summary, String description, String priority) {
        return createNote(caseToken, summary, description, priority, null);
    }

    /**
     * Create a note of the given type, one of {@link #CREATABLE_NOTE_TYPES}. A null type leaves
     * it out, so the server's default ({@code USER_PARTNER}) applies. Any other value, including
     * {@code SYSTEM}, is refused with {@link IllegalArgumentException} before any request.
     */
    CaseNoteDto createNote(String caseToken, String summary, String description, String priority, String type);
}

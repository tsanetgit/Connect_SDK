package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;

public interface CaseNotesFacade {
    List<CaseNoteDto> listNotesForRequest(String caseToken);

    List<CaseNoteDto> listNotesForAllRequests();

    List<CaseNoteDto> listStoredNotes();

    List<CaseNoteDto> listStoredNotesForRequest(String caseToken);

    /** Create a note with the server's default type, {@code USER_PARTNER}. */
    default CaseNoteDto createNote(String caseToken, String summary, String description, String priority) {
        return createNote(caseToken, summary, description, priority, null);
    }

    /**
     * Create a note of the given type: {@code USER_PARTNER} or {@code USER_PUBLIC}. A null type
     * leaves it out, so the server's default ({@code USER_PARTNER}) applies. {@code SYSTEM} is
     * reserved for platform notes and is refused with {@link IllegalArgumentException} before
     * any request.
     */
    CaseNoteDto createNote(String caseToken, String summary, String description, String priority, String type);
}

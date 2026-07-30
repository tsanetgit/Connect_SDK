package com.tsanet.api.facade;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;

public interface CaseNotesFacade {
    List<CaseNoteDto> listNotesForRequest(String caseToken);

    List<CaseNoteDto> listNotesForAllRequests();

    List<CaseNoteDto> listStoredNotes();

    List<CaseNoteDto> listStoredNotesForRequest(String caseToken);

    CaseNoteDto createNote(String caseToken, String summary, String description, String priority);
}

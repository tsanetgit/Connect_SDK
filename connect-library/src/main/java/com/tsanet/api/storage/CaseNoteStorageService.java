package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CaseNoteDto;
import java.util.List;

public class CaseNoteStorageService {
    private final CaseNoteRepository repository;

    public CaseNoteStorageService(CaseNoteRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(List<CaseNoteDto> notes) {
        repository.saveAll(notes);
    }

    public List<CaseNoteDto> findAll() {
        return repository.findAll();
    }

    public List<CaseNoteDto> findByCaseToken(String caseToken) {
        return repository.findByCaseToken(caseToken);
    }
}

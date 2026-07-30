package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CaseResponseDto;
import java.util.List;

public class CaseResponseStorageService {
    private final CaseResponseRepository repository;

    public CaseResponseStorageService(CaseResponseRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(List<CaseResponseDto> responses) {
        repository.saveAll(responses);
    }

    public List<CaseResponseDto> findAll() {
        return repository.findAll();
    }

    public List<CaseResponseDto> findByCaseToken(String caseToken) {
        return repository.findByCaseToken(caseToken);
    }
}

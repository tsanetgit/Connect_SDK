package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.PartnerSelectionDto;
import java.util.List;

public class PartnerSelectionStorageService {
    private final PartnerSelectionRepository repository;

    public PartnerSelectionStorageService(PartnerSelectionRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(String searchTerm, List<PartnerSelectionDto> partners) {
        repository.replaceForSearchTerm(searchTerm, partners);
    }

    public List<PartnerSelectionDto> findAll() {
        return repository.findAll();
    }

    public List<PartnerSelectionDto> findBySearchTerm(String searchTerm) {
        return repository.findBySearchTerm(searchTerm);
    }
}

package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.CollaborationRequestFormDto;
import java.util.List;

public class CollaborationRequestFormStorageService {
    private final CollaborationRequestFormRepository repository;

    public CollaborationRequestFormStorageService(CollaborationRequestFormRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(CollaborationRequestFormDto form) {
        repository.save(form);
    }

    public List<CollaborationRequestFormDto> findAll() {
        return repository.findAll();
    }

    public List<CollaborationRequestFormDto> findByReceiverCompanyId(long receiverCompanyId) {
        return repository.findByReceiverCompanyId(receiverCompanyId);
    }

    public List<CollaborationRequestFormDto> findByDocumentId(long documentId) {
        return repository.findByDocumentId(documentId);
    }
}

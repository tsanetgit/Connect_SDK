package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.AttachmentForwardResultDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentForwardResultDto;
import java.util.List;

public class AttachmentForwardResultStorageService {
    private final AttachmentForwardResultRepository repository;

    public AttachmentForwardResultStorageService(AttachmentForwardResultRepository repository) {
        this.repository = repository;
    }

    public void storeForwarded(String caseToken, String description, List<AttachmentForwardResultDto> results) {
        repository.saveAll(caseToken, description, results);
    }

    public List<StoredAttachmentForwardResultDto> findAll() {
        return repository.findAll();
    }

    public List<StoredAttachmentForwardResultDto> findByCaseToken(String caseToken) {
        return repository.findByCaseToken(caseToken);
    }
}

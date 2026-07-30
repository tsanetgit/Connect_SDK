package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.AttachmentConfigDto;
import com.tsanet.api.connectapi.dto.StoredAttachmentConfigDto;
import java.util.List;

public class AttachmentConfigStorageService {
    private final AttachmentConfigRepository repository;

    public AttachmentConfigStorageService(AttachmentConfigRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(String caseToken, AttachmentConfigDto config) {
        repository.save(caseToken, config);
    }

    public List<StoredAttachmentConfigDto> findAll() {
        return repository.findAll();
    }

    public List<StoredAttachmentConfigDto> findByCaseToken(String caseToken) {
        return repository.findByCaseToken(caseToken);
    }
}

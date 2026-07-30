package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.UserContextDto;
import java.util.List;

public class UserContextStorageService {
    private final UserContextRepository repository;

    public UserContextStorageService(UserContextRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(UserContextDto userContext) {
        repository.save(userContext);
    }

    public List<UserContextDto> findAll() {
        return repository.findAll();
    }
}

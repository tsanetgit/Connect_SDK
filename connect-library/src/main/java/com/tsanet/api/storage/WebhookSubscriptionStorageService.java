package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.WebhookSubscriptionDto;
import java.util.List;

public class WebhookSubscriptionStorageService {
    private final WebhookSubscriptionRepository repository;

    public WebhookSubscriptionStorageService(WebhookSubscriptionRepository repository) {
        this.repository = repository;
    }

    public void storeFetched(List<WebhookSubscriptionDto> subscriptions) {
        repository.saveAll(subscriptions);
    }

    public List<WebhookSubscriptionDto> findAll() {
        return repository.findAll();
    }

    public void storeSecret(Long id, String secret) {
        repository.saveSecret(id, secret);
    }

    public List<WebhookSubscriptionRepository.SecretRow> findVerificationSecrets() {
        return repository.findVerificationSecrets();
    }
}

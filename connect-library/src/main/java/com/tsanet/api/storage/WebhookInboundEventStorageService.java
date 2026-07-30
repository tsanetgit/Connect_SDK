package com.tsanet.api.storage;

import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import java.util.List;

public class WebhookInboundEventStorageService {
    private final WebhookInboundEventRepository repository;

    public WebhookInboundEventStorageService(WebhookInboundEventRepository repository) {
        this.repository = repository;
    }

    public WebhookInboundEventDto store(
        Long subscriptionId,
        String eventType,
        String requestToken,
        String noteToken,
        String eventTimestamp,
        boolean signatureValid,
        boolean cacheSynced,
        String syncMessage,
        String rawPayload
    ) {
        return repository.insert(
            subscriptionId,
            eventType,
            requestToken,
            noteToken,
            eventTimestamp,
            signatureValid,
            cacheSynced,
            syncMessage,
            rawPayload
        );
    }

    public List<WebhookInboundEventDto> findAll() {
        return repository.findAll();
    }
}

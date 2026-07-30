package com.tsanet.api.connectapi.dto;

public record WebhookInboundEventDto(
    Long id,
    Long subscriptionId,
    String eventType,
    String requestToken,
    String noteToken,
    String eventTimestamp,
    String receivedAt,
    boolean signatureValid,
    boolean cacheSynced,
    String syncMessage,
    String rawPayload
) {
}

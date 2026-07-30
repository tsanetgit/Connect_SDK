package com.tsanet.api.connectapi.dto;

public record WebhookSubscriptionDto(
    Long id,
    String callbackUrl,
    String eventTypes,
    Boolean active,
    String createdAt,
    String updatedAt
) {
}

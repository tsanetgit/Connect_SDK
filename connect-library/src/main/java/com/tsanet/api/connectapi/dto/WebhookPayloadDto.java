package com.tsanet.api.connectapi.dto;

public record WebhookPayloadDto(
    String eventType,
    String requestToken,
    String noteToken,
    String timestamp
) {
}

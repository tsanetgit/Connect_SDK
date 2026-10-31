package com.tsanet.api.connectapi.dto;

public record WebhookPayloadDto(
    String eventType,
    String requestToken,
    String noteToken,
    String timestamp
) {
    @Override
    public String toString() {
        return "WebhookPayloadDto[eventType=" + eventType
            + ", requestToken=<redacted>, noteToken=<redacted>, timestamp=" + timestamp + "]";
    }
}

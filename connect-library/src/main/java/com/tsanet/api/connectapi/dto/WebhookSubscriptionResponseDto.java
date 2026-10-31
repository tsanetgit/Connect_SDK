package com.tsanet.api.connectapi.dto;

public record WebhookSubscriptionResponseDto(
    Long id,
    String callbackUrl,
    String eventTypes,
    Boolean active,
    String secret,
    String createdAt
) {
    @Override
    public String toString() {
        return "WebhookSubscriptionResponseDto[id=" + id + ", callbackUrl=<redacted>, eventTypes="
            + eventTypes + ", active=" + active + ", secret=<redacted>, createdAt=" + createdAt + "]";
    }

}

package com.tsanet.api.connectapi.dto;

public record WebhookDeliveryDto(
    Long id,
    String cloudEventId,
    String eventType,
    Integer httpStatus,
    Integer attemptNumber,
    Boolean success,
    String requestBody,
    String responseBody,
    String createdAt
) {
}

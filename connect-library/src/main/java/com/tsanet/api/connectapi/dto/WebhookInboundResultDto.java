package com.tsanet.api.connectapi.dto;

public record WebhookInboundResultDto(
    boolean accepted,
    boolean signatureValid,
    String message,
    WebhookInboundEventDto event
) {
}

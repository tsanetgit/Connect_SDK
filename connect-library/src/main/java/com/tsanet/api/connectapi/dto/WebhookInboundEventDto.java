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
    @Override
    public String toString() {
        // The payload repeats capability tokens, and sync messages may include them too.
        return "WebhookInboundEventDto[id=" + id + ", subscriptionId=" + subscriptionId
            + ", eventType=" + eventType + ", requestToken=<redacted>, noteToken=<redacted>"
            + ", eventTimestamp=" + eventTimestamp + ", receivedAt=" + receivedAt
            + ", signatureValid=" + signatureValid + ", cacheSynced=" + cacheSynced
            + ", syncMessage=<redacted>, rawPayload=<redacted>]";
    }
}

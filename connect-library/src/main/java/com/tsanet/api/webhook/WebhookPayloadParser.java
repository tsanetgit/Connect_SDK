package com.tsanet.api.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsanet.api.connectapi.dto.WebhookPayloadDto;

public final class WebhookPayloadParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookPayloadParser() {
    }

    public static WebhookPayloadDto parse(String rawPayload) {
        try {
            JsonNode root = MAPPER.readTree(rawPayload);
            String eventType = text(root, "eventType");
            String requestToken = text(root, "requestToken");
            String noteToken = text(root, "noteToken");
            String timestamp = text(root, "timestamp");
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("Webhook payload missing eventType");
            }
            if (requestToken == null || requestToken.isBlank()) {
                throw new IllegalArgumentException("Webhook payload missing requestToken");
            }
            return new WebhookPayloadDto(eventType, requestToken, noteToken, timestamp);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid webhook payload JSON: " + ex.getMessage(), ex);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}

package com.tsanet.api.webhook;

import com.tsanet.api.connectapi.dto.WebhookInboundEventDto;
import com.tsanet.api.connectapi.dto.WebhookInboundResultDto;
import com.tsanet.api.connectapi.dto.WebhookPayloadDto;
import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.storage.WebhookInboundEventStorageService;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WebhookInboundService {
    public static final String EVENT_COLLABORATION_REQUEST_CREATED = "collaboration-request.created";
    public static final String EVENT_NOTE_CREATED = "note.created";

    private final WebhookSubscriptionStorageService subscriptionStorageService;
    private final WebhookInboundEventStorageService inboundEventStorageService;
    private final ConnectApiCollaborationGateway collaborationGateway;
    private final ConnectApiNotesGateway notesGateway;
    private final Runnable ensureAuthenticated;

    public WebhookInboundService(
        WebhookSubscriptionStorageService subscriptionStorageService,
        WebhookInboundEventStorageService inboundEventStorageService,
        ConnectApiCollaborationGateway collaborationGateway,
        ConnectApiNotesGateway notesGateway,
        Runnable ensureAuthenticated
    ) {
        this.subscriptionStorageService = subscriptionStorageService;
        this.inboundEventStorageService = inboundEventStorageService;
        this.collaborationGateway = collaborationGateway;
        this.notesGateway = notesGateway;
        this.ensureAuthenticated = ensureAuthenticated;
    }

    public WebhookInboundResultDto receive(String signatureHeader, String rawBody) {
        byte[] payloadBytes = rawBody.getBytes(StandardCharsets.UTF_8);
        List<WebhookSignatureVerifier.SecretCandidate> secrets = subscriptionStorageService.findVerificationSecrets()
            .stream()
            .map(row -> new WebhookSignatureVerifier.SecretCandidate(row.subscriptionId(), row.secret()))
            .toList();

        Long subscriptionId = WebhookSignatureVerifier.matchingSubscriptionId(payloadBytes, signatureHeader, secrets);
        if (subscriptionId == null) {
            WebhookInboundEventDto event = inboundEventStorageService.store(
                null,
                "unknown",
                "unknown",
                null,
                null,
                false,
                false,
                "Invalid or missing webhook signature",
                rawBody
            );
            return new WebhookInboundResultDto(false, false, "Invalid or missing webhook signature", event);
        }

        WebhookPayloadDto payload;
        try {
            payload = WebhookPayloadParser.parse(rawBody);
        } catch (IllegalArgumentException ex) {
            WebhookInboundEventDto event = inboundEventStorageService.store(
                subscriptionId,
                "invalid",
                "unknown",
                null,
                null,
                true,
                false,
                ex.getMessage(),
                rawBody
            );
            return new WebhookInboundResultDto(false, true, ex.getMessage(), event);
        }

        String syncMessage = syncCache(payload);
        boolean cacheSynced = syncMessage.startsWith("Synced");
        WebhookInboundEventDto event = inboundEventStorageService.store(
            subscriptionId,
            payload.eventType(),
            payload.requestToken(),
            payload.noteToken(),
            payload.timestamp(),
            true,
            cacheSynced,
            syncMessage,
            rawBody
        );
        return new WebhookInboundResultDto(true, true, syncMessage, event);
    }

    private String syncCache(WebhookPayloadDto payload) {
        try {
            ensureAuthenticated.run();
            if (EVENT_COLLABORATION_REQUEST_CREATED.equals(payload.eventType())) {
                collaborationGateway.getCollaborationRequestByToken(payload.requestToken());
                return "Synced collaboration request token=" + payload.requestToken();
            }
            if (EVENT_NOTE_CREATED.equals(payload.eventType())) {
                collaborationGateway.getCollaborationRequestByToken(payload.requestToken());
                notesGateway.getNotes(payload.requestToken());
                return "Synced notes for request token=" + payload.requestToken();
            }
            return "Stored webhook event without cache sync for eventType=" + payload.eventType();
        } catch (Exception ex) {
            return "Stored webhook event; cache sync failed: " + ex.getMessage();
        }
    }
}

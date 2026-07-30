package com.tsanet.facade.web;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.WebhookInboundResultDto;
import com.tsanet.api.webhook.WebhookSignatureVerifier;
import com.tsanet.facade.config.WebhookProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "tsanet.webhook", name = "enabled", havingValue = "true")
public class WebhookReceiverController {
    private final TsaNetApiSession session;
    private final WebhookProperties webhookProperties;

    public WebhookReceiverController(TsaNetApiSession session, WebhookProperties webhookProperties) {
        this.session = session;
        this.webhookProperties = webhookProperties;
    }

    @PostMapping("${tsanet.webhook.path:/webhooks/tsanet}")
    public ResponseEntity<String> receiveWebhook(
        @RequestHeader(value = WebhookSignatureVerifier.SIGNATURE_HEADER, required = false) String signature,
        @RequestBody String body
    ) {
        WebhookInboundResultDto result = session.webhooks().receiveInbound(signature, body);
        if (!result.signatureValid()) {
            return ResponseEntity.status(401).body(result.message());
        }
        if (!result.accepted()) {
            return ResponseEntity.badRequest().body(result.message());
        }
        return ResponseEntity.ok(result.message());
    }

    public String callbackUrl() {
        return webhookProperties.callbackUrl();
    }
}

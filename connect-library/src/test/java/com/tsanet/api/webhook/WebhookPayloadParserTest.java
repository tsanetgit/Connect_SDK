package com.tsanet.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookPayloadParserTest {
    @Test
    void itParsesValidPayload() {
        var payload = WebhookPayloadParser.parse(
            """
            {"eventType":"collaboration-request.created","requestToken":"tok1","timestamp":"2026-01-01T00:00:00Z"}
            """
        );

        assertThat(payload.eventType()).isEqualTo("collaboration-request.created");
        assertThat(payload.requestToken()).isEqualTo("tok1");
    }

    @Test
    void itRejectsMissingRequestToken() {
        assertThatThrownBy(() -> WebhookPayloadParser.parse("{\"eventType\":\"note.created\"}"))
            .hasMessageContaining("requestToken");
    }
}

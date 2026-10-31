package com.tsanet.api.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.connectapi.internal.ConnectApiCollaborationGateway;
import com.tsanet.api.connectapi.internal.ConnectApiNotesGateway;
import com.tsanet.api.storage.WebhookInboundEventStorageService;
import com.tsanet.api.storage.WebhookSubscriptionRepository.SecretRow;
import com.tsanet.api.storage.WebhookSubscriptionStorageService;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebhookInboundServiceTest {
    private static final String SECRET = "test-webhook-secret";

    @ParameterizedTest
    @CsvSource({
        "collaboration-request.created, Synced collaboration request",
        "note.created, Synced notes for request"
    })
    void aSuccessfulSyncIsMarkedSyncedWithoutTheRequestToken(String eventType, String message) throws Exception {
        var subscriptions = mock(WebhookSubscriptionStorageService.class);
        when(subscriptions.findVerificationSecrets()).thenReturn(List.of(new SecretRow(7L, SECRET)));
        var events = mock(WebhookInboundEventStorageService.class);
        var service = new WebhookInboundService(subscriptions, events,
            mock(ConnectApiCollaborationGateway.class), mock(ConnectApiNotesGateway.class), () -> { });
        String body = "{\"eventType\":\"" + eventType + "\",\"requestToken\":\"tok-sync\"}";

        var result = service.receive(signature(body), body);

        assertThat(result.message()).isEqualTo(message);
        verify(events).store(eq(7L), eq(eventType), eq("tok-sync"), any(), any(), eq(true), eq(true), eq(message), eq(body));
    }

    private static String signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}

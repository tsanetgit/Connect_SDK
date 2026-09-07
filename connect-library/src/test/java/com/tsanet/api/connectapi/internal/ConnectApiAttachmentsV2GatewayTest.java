package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadReceipts;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The flow rules, with the Connect API seam and the executor mocked: abandon on upload
 * failure, one re-grant on a complete-time grant-expired, complete retried on transient
 * failure, the platform's outcome returned unchanged, and the digest computed only on
 * request.
 */
@ExtendWith(MockitoExtension.class)
class ConnectApiAttachmentsV2GatewayTest {

    private static final String TOKEN = "case-1";
    private static final UUID GRANT_A = UUID.randomUUID();
    private static final UUID GRANT_B = UUID.randomUUID();

    @Mock
    private AttachmentsV2Api api;
    @Mock
    private AttachmentUploadExecutor executor;

    private ConnectApiAttachmentsV2Gateway gateway;
    private Path file;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new ConnectApiAttachmentsV2Gateway(api, GatewayTestSupport.authenticatedSessionStore(), executor);
        file = tmp.resolve("diag.log");
        Files.write(file, "hello attachments".getBytes(StandardCharsets.UTF_8));
    }

    private static AttachmentGrant grant(UUID id) {
        return new AttachmentGrant(id, "diag.log", OffsetDateTime.now().plusMinutes(15), null,
            new AttachmentGrant.Upload("single", "PUT", "https://store.example/x", Map.of(), null),
            new AttachmentGrant.Verification("platform"));
    }

    private static AttachmentCompleteResult outcome(UUID id, String status) {
        return new AttachmentCompleteResult(id, "diag.log", status, null, 9L, null);
    }

    @Test
    void sendGrantsUploadsAndCompletesWithTheReceiptsAndReturnsThePlatformsOutcome() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("multipart", 17,
            List.of(new AttachmentCompleteRequest.PartReceipt(1, "\"e1\""))));
        when(api.complete(eq(TOKEN), eq(GRANT_A), any())).thenReturn(outcome(GRANT_A, "DELIVERED_UNVERIFIED"));

        AttachmentCompleteResult result = gateway.send(TOKEN, file, "text/plain", "  logs ", false, null);

        assertThat(result.status()).isEqualTo("DELIVERED_UNVERIFIED");
        ArgumentCaptor<AttachmentGrantRequest> request = ArgumentCaptor.forClass(AttachmentGrantRequest.class);
        verify(api).createGrant(eq(TOKEN), request.capture(), anyString());
        assertThat(request.getValue().fileName()).isEqualTo("diag.log");
        assertThat(request.getValue().contentType()).isEqualTo("text/plain");
        assertThat(request.getValue().sizeBytes()).isEqualTo(17);
        assertThat(request.getValue().sha256()).isNull();
        assertThat(request.getValue().description()).isEqualTo("logs");
        ArgumentCaptor<AttachmentCompleteRequest> complete = ArgumentCaptor.forClass(AttachmentCompleteRequest.class);
        verify(api).complete(eq(TOKEN), eq(GRANT_A), complete.capture());
        assertThat(complete.getValue().sizeBytes()).isEqualTo(17);
        assertThat(complete.getValue().parts()).extracting(AttachmentCompleteRequest.PartReceipt::receipt).containsExactly("\"e1\"");
        verify(api, never()).abandon(any(), any());
    }

    @Test
    void sendComputesTheDigestOnlyWhenAskedAndForwardsItOnGrantAndComplete() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("single", 17, List.of()));
        when(api.complete(eq(TOKEN), eq(GRANT_A), any())).thenReturn(outcome(GRANT_A, "DELIVERED"));

        gateway.send(TOKEN, file, null, null, true, null);

        String expected = ConnectApiAttachmentsV2Gateway.sha256Of(file);
        assertThat(expected).hasSize(64);
        ArgumentCaptor<AttachmentGrantRequest> request = ArgumentCaptor.forClass(AttachmentGrantRequest.class);
        verify(api).createGrant(eq(TOKEN), request.capture(), anyString());
        assertThat(request.getValue().sha256()).isEqualTo(expected);
        assertThat(request.getValue().contentType()).isEqualTo(ConnectApiAttachmentsV2Gateway.DEFAULT_CONTENT_TYPE);
        ArgumentCaptor<AttachmentCompleteRequest> complete = ArgumentCaptor.forClass(AttachmentCompleteRequest.class);
        verify(api).complete(eq(TOKEN), eq(GRANT_A), complete.capture());
        assertThat(complete.getValue().sha256()).isEqualTo(expected);
    }

    @Test
    void anUploadFailureAbandonsTheGrantAndNeverCompletes() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        AttachmentV2Exception rejected = new AttachmentV2Exception("part 1 was rejected", 403, AttachmentV2Exception.UPLOAD_REJECTED);
        when(executor.execute(any(), eq(file), any())).thenThrow(rejected);

        assertThatThrownBy(() -> gateway.send(TOKEN, file, "text/plain", null, false, null)).isSameAs(rejected);

        verify(api).abandon(TOKEN, GRANT_A);
        verify(api, never()).complete(any(), any(), any());
    }

    @Test
    void anAbandonFailureRidesAsSuppressedOnTheUploadFailure() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        when(executor.execute(any(), eq(file), any()))
            .thenThrow(new AttachmentV2Exception("unreachable", 0, AttachmentV2Exception.UPLOAD_UNREACHABLE));
        doThrow(new AttachmentV2Exception("abandon failed", 503, null)).when(api).abandon(TOKEN, GRANT_A);

        assertThatThrownBy(() -> gateway.send(TOKEN, file, "text/plain", null, false, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessage("unreachable")
            .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1)
                .allSatisfy(s -> assertThat(s.getMessage()).isEqualTo("abandon failed")));
    }

    @Test
    void aGrantExpiredOnCompleteRegrantsExactlyOnce() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A), grant(GRANT_B));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("single", 17, List.of()));
        when(api.complete(eq(TOKEN), eq(GRANT_A), any()))
            .thenThrow(new AttachmentV2Exception("expired", 409, AttachmentV2Exception.GRANT_EXPIRED));
        when(api.complete(eq(TOKEN), eq(GRANT_B), any())).thenReturn(outcome(GRANT_B, "DELIVERED"));

        AttachmentCompleteResult result = gateway.send(TOKEN, file, "text/plain", null, false, null);

        assertThat(result.grantId()).isEqualTo(GRANT_B);
        verify(api, times(2)).createGrant(eq(TOKEN), any(), anyString());
        verify(executor, times(2)).execute(any(), eq(file), any());
    }

    @Test
    void aSecondGrantExpiredIsNotRetriedAgain() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A), grant(GRANT_B));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("single", 17, List.of()));
        when(api.complete(eq(TOKEN), any(), any()))
            .thenThrow(new AttachmentV2Exception("expired", 409, AttachmentV2Exception.GRANT_EXPIRED));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, "text/plain", null, false, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).isProblem(AttachmentV2Exception.GRANT_EXPIRED)).isTrue());
        verify(api, times(2)).createGrant(eq(TOKEN), any(), anyString());
    }

    @Test
    void completeIsRetriedOnTransientFailureBecauseItIsIdempotent() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("single", 17, List.of()));
        when(api.complete(eq(TOKEN), eq(GRANT_A), any()))
            .thenThrow(new AttachmentV2Exception("gateway", 502, null))
            .thenThrow(new AttachmentV2Exception("down", 0, AttachmentV2Exception.CONNECTIVITY))
            .thenReturn(outcome(GRANT_A, "FAILED"));

        AttachmentCompleteResult result = gateway.send(TOKEN, file, "text/plain", null, false, null);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(api, times(3)).complete(eq(TOKEN), eq(GRANT_A), any());
    }

    @Test
    void aFourHundredOnCompleteIsNotRetried() {
        when(api.createGrant(eq(TOKEN), any(), anyString())).thenReturn(grant(GRANT_A));
        when(executor.execute(any(), eq(file), any())).thenReturn(new UploadReceipts("single", 17, List.of()));
        when(api.complete(eq(TOKEN), eq(GRANT_A), any()))
            .thenThrow(new AttachmentV2Exception("size mismatch", 422, AttachmentV2Exception.SIZE_MISMATCH));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, "text/plain", null, false, null))
            .isInstanceOf(AttachmentV2Exception.class);
        verify(api, times(1)).complete(eq(TOKEN), eq(GRANT_A), any());
    }

    @Test
    void everyCallRequiresALogin() {
        ConnectApiAttachmentsV2Gateway loggedOut =
            new ConnectApiAttachmentsV2Gateway(api, new ConnectApiSessionStore(), executor);

        assertThatThrownBy(() -> loggedOut.grant(TOKEN, new AttachmentGrantRequest("a", "b", 1, null, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loggedOut.send(TOKEN, file, null, null, false, null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loggedOut.abandon(TOKEN, GRANT_A)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendRejectsAMissingFileBeforeGranting() {
        assertThatThrownBy(() -> gateway.send(TOKEN, tmp.resolve("missing.bin"), null, null, false, null))
            .isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).createGrant(any(), any(), any());
    }
}

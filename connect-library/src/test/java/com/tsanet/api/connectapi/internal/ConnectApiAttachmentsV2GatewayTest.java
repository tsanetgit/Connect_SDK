package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadReceipts;
import com.tsanet.api.generated.api.AttachmentGrantsApi;
import com.tsanet.api.generated.model.AttachmentGrantCreateRequestDTO;
import com.tsanet.api.generated.model.AttachmentGrantDTO;
import com.tsanet.api.generated.model.AttachmentGrantStatus;
import com.tsanet.api.generated.model.AttachmentUploadMode;
import com.tsanet.api.generated.model.S3MultipartCompletionRequestDTO;
import com.tsanet.api.generated.model.S3MultipartPlanDTO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The flow rules of {@code send}, with the generated API and the upload coordinator mocked:
 * the grant asks for the file's name and size, complete follows the grant's mode with the S3
 * receipts, a failure abandons the grant (except a 409, where it is already terminal),
 * complete is retried on a 502 or a lost response, and nothing else is retried.
 */
@ExtendWith(MockitoExtension.class)
class ConnectApiAttachmentsV2GatewayTest {

    private static final String TOKEN = "case-1";
    private static final long GRANT_ID = 77L;

    @Mock
    private AttachmentGrantsApi api;
    @Mock
    private UploadCoordinator coordinator;

    private ConnectApiAttachmentsV2Gateway gateway;
    private Path file;

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        gateway = new ConnectApiAttachmentsV2Gateway(api, GatewayTestSupport.authenticatedSessionStore(), coordinator,
            Duration.ZERO);
        file = tmp.resolve("diag.log");
        Files.write(file, "hello attachments".getBytes(StandardCharsets.UTF_8));
    }

    private static AttachmentGrantDTO grantDto(AttachmentUploadMode mode, AttachmentGrantStatus status) {
        AttachmentGrantDTO dto = new AttachmentGrantDTO()
            .grantId(GRANT_ID)
            .status(status)
            .fileName("diag.log")
            .expectedSizeBytes(17L)
            .createdAt(OffsetDateTime.parse("2026-10-01T12:00:00Z"))
            .expiresAt(OffsetDateTime.parse("2026-10-01T13:00:00Z"))
            .mode(mode);
        if (mode == AttachmentUploadMode.S3_MULTIPART) {
            dto.s3Multipart(new S3MultipartPlanDTO().totalParts(2).partSizeBytes(10L));
        }
        return dto;
    }

    private static ConnectApiException apiError(int status) {
        return new ConnectApiException(ConnectApiException.Kind.OTHER, status, null, null, "HTTP " + status, null, null);
    }

    private static UploadReceipts s3Receipts() {
        return new UploadReceipts(AttachmentGrant.UploadMode.S3_MULTIPART, 17, List.of(
            new UploadReceipts.PartReceipt(1, "\"e1\""), new UploadReceipts.PartReceipt(2, "\"e2\"")));
    }

    @Test
    void sendGrantsForTheFilesNameAndSizeUploadsAndCompletesWithTheReceipts() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.S3_MULTIPART,
            AttachmentGrantStatus.OPEN));
        when(coordinator.upload(any(), eq(file), any(), any())).thenReturn(s3Receipts());
        when(api.completeS3Multipart(eq(TOKEN), eq(GRANT_ID), any())).thenReturn(grantDto(AttachmentUploadMode.S3_MULTIPART,
            AttachmentGrantStatus.COMPLETED));

        AttachmentGrant result = gateway.send(TOKEN, file, null);

        assertThat(result.completed()).isTrue();
        assertThat(result.plan()).isEqualTo(new AttachmentGrant.UploadPlan(2, 10));
        ArgumentCaptor<AttachmentGrantCreateRequestDTO> request = ArgumentCaptor.forClass(AttachmentGrantCreateRequestDTO.class);
        verify(api).createAttachmentGrant(eq(TOKEN), request.capture());
        assertThat(request.getValue().getFileName()).isEqualTo("diag.log");
        assertThat(request.getValue().getExpectedSizeBytes()).isEqualTo(17L);
        ArgumentCaptor<S3MultipartCompletionRequestDTO> complete = ArgumentCaptor.forClass(S3MultipartCompletionRequestDTO.class);
        verify(api).completeS3Multipart(eq(TOKEN), eq(GRANT_ID), complete.capture());
        assertThat(complete.getValue().getParts()).extracting("partNumber", "etag")
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "\"e1\""), org.assertj.core.groups.Tuple.tuple(2, "\"e2\""));
        verify(api, never()).abandonAttachmentGrant(anyString(), anyLong());
    }

    @Test
    void completeFollowsTheGrantsMode() {
        when(api.completeSingleUpload(TOKEN, GRANT_ID)).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.COMPLETED));
        when(api.completeAzureBlockUpload(TOKEN, GRANT_ID)).thenReturn(grantDto(AttachmentUploadMode.AZURE_BLOCK,
            AttachmentGrantStatus.COMPLETED));

        gateway.complete(TOKEN, ConnectApiAttachmentsV2Gateway.toGrant(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN)), null);
        gateway.complete(TOKEN, ConnectApiAttachmentsV2Gateway.toGrant(grantDto(AttachmentUploadMode.AZURE_BLOCK,
            AttachmentGrantStatus.OPEN)), null);

        verify(api).completeSingleUpload(TOKEN, GRANT_ID);
        verify(api).completeAzureBlockUpload(TOKEN, GRANT_ID);
        assertThatThrownBy(() -> gateway.complete(TOKEN, ConnectApiAttachmentsV2Gateway.toGrant(
            grantDto(AttachmentUploadMode.GCS_RESUMABLE, AttachmentGrantStatus.OPEN)), null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code())
                .isEqualTo(AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE));
    }

    @Test
    void anUploadFailureAbandonsTheGrantAndNeverCompletes() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        AttachmentV2Exception failure = new AttachmentV2Exception("part 1: link expired", 0,
            AttachmentV2Exception.LINK_NOT_REFRESHABLE);
        when(coordinator.upload(any(), eq(file), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null)).isSameAs(failure);

        verify(api).abandonAttachmentGrant(TOKEN, GRANT_ID);
        verify(api, never()).completeSingleUpload(anyString(), anyLong());
    }

    @Test
    void anAbandonFailureRidesAsSuppressedOnTheOriginalFailure() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        AttachmentV2Exception failure = new AttachmentV2Exception("rejected", 400, AttachmentV2Exception.UPLOAD_REJECTED);
        when(coordinator.upload(any(), eq(file), any(), any())).thenThrow(failure);
        when(api.abandonAttachmentGrant(TOKEN, GRANT_ID)).thenThrow(apiError(500));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null)).isSameAs(failure);

        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(((AttachmentV2Exception) failure.getSuppressed()[0]).status()).isEqualTo(500);
    }

    @Test
    void aCompleteMismatchAbandonsTheGrant() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID)).thenThrow(apiError(422));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.UPLOAD_MISMATCH));

        verify(api).completeSingleUpload(TOKEN, GRANT_ID);
        verify(api).abandonAttachmentGrant(TOKEN, GRANT_ID);
    }

    @Test
    void aTerminalGrantOnCompleteIsNotAbandoned() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID)).thenThrow(apiError(409));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.GRANT_TERMINAL));

        verify(api, never()).abandonAttachmentGrant(anyString(), anyLong());
    }

    @Test
    void aTerminalGrantDuringTheUploadIsNotAbandonedEither() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        AttachmentV2Exception expired = new AttachmentV2Exception("sign single upload link failed: HTTP 409", 409,
            AttachmentV2Exception.GRANT_TERMINAL);
        when(coordinator.upload(any(), eq(file), any(), any())).thenThrow(expired);

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null)).isSameAs(expired);

        verify(api, never()).abandonAttachmentGrant(anyString(), anyLong());
    }

    @Test
    void aMissingRequiredFieldIsNamedNotDereferenced() {
        AttachmentGrantDTO noId = grantDto(AttachmentUploadMode.SINGLE, AttachmentGrantStatus.OPEN).grantId(null);
        when(api.getAttachmentGrant(TOKEN, GRANT_ID)).thenReturn(noId);
        when(api.signSingleUploadUrl(TOKEN, GRANT_ID)).thenReturn(new com.tsanet.api.generated.model.SingleUploadUrlDTO()
            .expiresAt(OffsetDateTime.parse("2026-10-01T12:30:00Z")));

        assertThatThrownBy(() -> gateway.getGrant(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("grantId");
        assertThatThrownBy(() -> gateway.singleUploadLink(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("url");
    }

    @Test
    void aGrantWithNoModeIsRefusedAsUnsupportedBeforeAnyCall() {
        AttachmentGrant noMode = new AttachmentGrant(GRANT_ID, AttachmentGrant.Status.OPEN, "diag.log", 17, null, null,
            null, null);

        assertThatThrownBy(() -> gateway.upload(TOKEN, noMode, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code())
                .isEqualTo(AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE));
        verifyNoInteractions(api, coordinator);
    }

    @Test
    void aLostCompleteResponseIsRetriedAndTheAlreadyCompletedGrantIsSuccess() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID))
            .thenThrow(ConnectApiException.connectivity(new java.net.SocketTimeoutException("read timed out")))
            .thenReturn(grantDto(AttachmentUploadMode.SINGLE, AttachmentGrantStatus.COMPLETED));

        AttachmentGrant result = gateway.send(TOKEN, file, null);

        assertThat(result.completed()).isTrue();
        verify(api, times(2)).completeSingleUpload(TOKEN, GRANT_ID);
        verify(api, never()).abandonAttachmentGrant(anyString(), anyLong());
    }

    @Test
    void aCompleteWhoseAnswersWereAllLostButLandedIsADelivery() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID))
            .thenThrow(ConnectApiException.connectivity(new java.net.SocketTimeoutException("read timed out")));
        when(api.abandonAttachmentGrant(TOKEN, GRANT_ID)).thenThrow(apiError(409));
        when(api.getAttachmentGrant(TOKEN, GRANT_ID)).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.COMPLETED));

        AttachmentGrant result = gateway.send(TOKEN, file, null);

        assertThat(result.completed()).isTrue();
        verify(api, times(3)).completeSingleUpload(TOKEN, GRANT_ID);
        verify(api).getAttachmentGrant(TOKEN, GRANT_ID);
    }

    @Test
    void aLostCompleteOnAGrantThatDidNotCompleteStaysAFailure() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID))
            .thenThrow(ConnectApiException.connectivity(new java.net.SocketTimeoutException("read timed out")));
        when(api.abandonAttachmentGrant(TOKEN, GRANT_ID)).thenThrow(apiError(409));
        when(api.getAttachmentGrant(TOKEN, GRANT_ID)).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.EXPIRED));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.CONNECTIVITY);
                assertThat(ex.getSuppressed()).hasSize(1);
                assertThat(((AttachmentV2Exception) ex.getSuppressed()[0]).code())
                    .isEqualTo(AttachmentV2Exception.GRANT_TERMINAL);
            });
    }

    @Test
    void aProviderErrorOnCompleteIsRetriedThenAbandonedWhenItPersists() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));
        when(api.completeSingleUpload(TOKEN, GRANT_ID)).thenThrow(apiError(502));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.PROVIDER_ERROR));

        verify(api, times(3)).completeSingleUpload(TOKEN, GRANT_ID);
        verify(api).abandonAttachmentGrant(TOKEN, GRANT_ID);
    }

    @Test
    void aBadRequestOnCompleteIsNotRetried() {
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.S3_MULTIPART,
            AttachmentGrantStatus.OPEN));
        when(coordinator.upload(any(), eq(file), any(), any())).thenReturn(s3Receipts());
        when(api.completeS3Multipart(eq(TOKEN), eq(GRANT_ID), any())).thenThrow(apiError(400));

        assertThatThrownBy(() -> gateway.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.INVALID_REQUEST));

        verify(api, times(1)).completeS3Multipart(eq(TOKEN), eq(GRANT_ID), any());
    }

    @Test
    void linkCallsTakeBetweenOneAndAThousandNumbers() {
        List<Integer> tooMany = IntStream.rangeClosed(1, 1001).boxed().toList();

        assertThatThrownBy(() -> gateway.s3PartLinks(TOKEN, GRANT_ID, tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.azureBlockLinks(TOKEN, GRANT_ID, List.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);
    }

    @Test
    void everyCallRequiresALogin() {
        ConnectApiAttachmentsV2Gateway loggedOut = new ConnectApiAttachmentsV2Gateway(api, new ConnectApiSessionStore(),
            coordinator, Duration.ZERO);

        assertThatThrownBy(() -> loggedOut.createGrant(TOKEN, "a", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loggedOut.send(TOKEN, file, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loggedOut.abandon(TOKEN, GRANT_ID)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(api);
    }

    @Test
    void sendRejectsAMissingFileBeforeGranting() {
        assertThatThrownBy(() -> gateway.send(TOKEN, tmp.resolve("missing.bin"), null))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);
    }

    // ---------- the receiver allowlist (tsanetgit/Connect_SDK#92) ----------

    private static final long ALLOWED = 1112L;
    private static final long NOT_ALLOWED = 1113L;

    private final List<String> caseLookups = new java.util.ArrayList<>();

    private ConnectApiAttachmentsV2Gateway allowlisted(java.util.Set<Long> allowed, Long receivingCompany) {
        return new ConnectApiAttachmentsV2Gateway(api, GatewayTestSupport.authenticatedSessionStore(), coordinator,
            Duration.ZERO, allowed, caseToken -> {
                caseLookups.add(caseToken);
                return java.util.Optional.ofNullable(receivingCompany);
            });
    }

    @Test
    void aReceiverOffTheAllowlistIsRefusedBeforeAnyGrantRequest() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(java.util.Set.of(ALLOWED), NOT_ALLOWED);

        assertThatThrownBy(() -> guarded.createGrant(TOKEN, "diag.log", 17))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.RECEIVER_NOT_ALLOWED));
        verifyNoInteractions(api);
    }

    @Test
    void sendIsRefusedTheSameWayAndUploadsNothing() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(java.util.Set.of(ALLOWED), NOT_ALLOWED);

        assertThatThrownBy(() -> guarded.send(TOKEN, file, null))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.RECEIVER_NOT_ALLOWED));
        verifyNoInteractions(api, coordinator);
    }

    @Test
    void aReceiverOnTheAllowlistGetsItsGrant() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(java.util.Set.of(ALLOWED), ALLOWED);
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));

        assertThat(guarded.createGrant(TOKEN, "diag.log", 17).grantId()).isEqualTo(GRANT_ID);
        assertThat(caseLookups).containsExactly(TOKEN);
    }

    @Test
    void anEmptyAllowlistIsUnrestrictedAndNeverReadsTheCase() {
        ConnectApiAttachmentsV2Gateway open = allowlisted(java.util.Set.of(), NOT_ALLOWED);
        when(api.createAttachmentGrant(eq(TOKEN), any())).thenReturn(grantDto(AttachmentUploadMode.SINGLE,
            AttachmentGrantStatus.OPEN));

        assertThat(open.createGrant(TOKEN, "diag.log", 17).grantId()).isEqualTo(GRANT_ID);
        assertThat(caseLookups).isEmpty();
    }

    @Test
    void aCaseWithNoReceivingCompanyFailsClosedAsAPrecondition() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(java.util.Set.of(ALLOWED), null);

        assertThatThrownBy(() -> guarded.createGrant(TOKEN, "diag.log", 17))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.CLIENT_PRECONDITION));
        verifyNoInteractions(api);
    }

    @Test
    void aCaseLookupThatFailsKeepsItsOwnCodeAndRequestsNoGrant() {
        ConnectApiAttachmentsV2Gateway guarded = new ConnectApiAttachmentsV2Gateway(api,
            GatewayTestSupport.authenticatedSessionStore(), coordinator, Duration.ZERO, java.util.Set.of(ALLOWED),
            caseToken -> {
                throw apiError(404);
            });

        assertThatThrownBy(() -> guarded.createGrant(TOKEN, "diag.log", 17))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.NOT_FOUND));
        verifyNoInteractions(api);
    }
}

package com.tsanet.api.connectapi.internal;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrant.UploadMode;
import com.tsanet.api.attachments.v2.AttachmentGrantPage;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import com.tsanet.api.facade.AttachmentsV2Facade;
import com.tsanet.api.generated.api.AttachmentGrantsApi;
import com.tsanet.api.generated.api.CollaborationRequestsApi;
import com.tsanet.api.generated.model.AttachmentGrantCreateRequestDTO;
import com.tsanet.api.generated.model.AttachmentGrantDTO;
import com.tsanet.api.generated.model.AttachmentGrantPageDTO;
import com.tsanet.api.generated.model.AzureBlockDTO;
import com.tsanet.api.generated.model.AzureBlockPlanDTO;
import com.tsanet.api.generated.model.AzureBlockSignRequestDTO;
import com.tsanet.api.generated.model.AzureBlocksDTO;
import com.tsanet.api.generated.model.CollaborationRequestDirection;
import com.tsanet.api.generated.model.S3MultipartCompletionRequestDTO;
import com.tsanet.api.generated.model.S3MultipartPartReceiptDTO;
import com.tsanet.api.generated.model.S3MultipartPlanDTO;
import com.tsanet.api.generated.model.S3PartDTO;
import com.tsanet.api.generated.model.S3PartSignRequestDTO;
import com.tsanet.api.generated.model.S3PartsDTO;
import com.tsanet.api.generated.model.SingleUploadUrlDTO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link AttachmentsV2Facade} over the generated {@link AttachmentGrantsApi} and the
 * {@link UploadCoordinator}. Generated DTOs stay inside this class: every one is mapped to an
 * SDK record on arrival, field by field through its getters, so renaming a field in the spec
 * breaks this build instead of a caller at runtime. A generated link DTO's {@code toString}
 * prints its URL, so none is ever logged or put in a message.
 *
 * <p>Errors from the Connect API become {@link AttachmentV2Exception}s whose code follows the
 * HTTP status, as the spec documents it for these endpoints, with one exception: on grant
 * creation, the server's allowlist refusal (a {@code 403} with its own problem type) is
 * {@code attachment/receiver-not-allowed}. With a receiver allowlist, the case read that
 * precedes grant creation is a V1 call, and its errors map by the same status rules.
 */
public class ConnectApiAttachmentsV2Gateway implements AttachmentsV2Facade {

    private static final int COMPLETE_ATTEMPTS = 3;

    // PROVISIONAL(tsanetgit/Connect-API-Code#183): the spec doesn't document this problem type;
    // the server's ProblemDetailFactory sends it for an allowlist refusal. Once the spec documents
    // createAttachmentGrant's 403 types, this becomes the documented value. Matched as the type's
    // last segment (ConnectApiErrors.isProblem), so the URL's host doesn't matter.
    private static final String RECEIVER_NOT_ALLOWED_TYPE = "attachment-receiver-not-allowed";

    private final AttachmentGrantsApi api;
    private final ConnectApiSessionStore sessionStore;
    private final UploadCoordinator coordinator;
    private final Duration completeBackoff;
    private final Set<Long> allowedReceiverCompanyIds;
    private final Function<String, Optional<CaseSide>> receivingCompanyOf;

    /**
     * What the allowlist check needs from a case, as this account reads it.
     *
     * <p>Skipping an {@code INBOUND} case relies on the server pairing two rules: it reads a case
     * as {@code OUTBOUND} only for the company that submitted it, and it refuses grant creation by
     * any company but the submitter before its own allowlist is consulted. A case read as
     * {@code INBOUND} therefore never gets a grant, with or without this check.
     *
     * @param inbound          the case reads {@code INBOUND}: this account is its receiver, not its
     *                         sender. False when it reads {@code OUTBOUND} or the answer has no direction
     * @param receiveCompanyId the case's receiving company; null when the answer doesn't say
     */
    public record CaseSide(boolean inbound, Long receiveCompanyId) {
    }

    /**
     * The case lookup the runtime gives the allowlist check: the case read straight from the API,
     * so nothing is cached. The server sets a case's direction relative to the reader, so
     * {@code INBOUND} means this account didn't send it.
     */
    public static Function<String, Optional<CaseSide>> receivingCompanyFrom(CollaborationRequestsApi api) {
        return caseToken -> Optional.ofNullable(api.getCollaborationRequestByToken(caseToken, false))
            .map(dto -> new CaseSide(isInbound(dto.getDirection()), dto.getReceiveCompanyId()));
    }

    private static boolean isInbound(CollaborationRequestDirection direction) {
        if (direction == null) {
            return false;
        }
        return switch (direction) {
            case INBOUND -> true;
            case OUTBOUND -> false;
        };
    }

    /** No receiver allowlist: every receiver the server accepts is allowed. */
    public ConnectApiAttachmentsV2Gateway(AttachmentGrantsApi api, ConnectApiSessionStore sessionStore) {
        this(api, sessionStore, Set.of(), caseToken -> Optional.empty());
    }

    /**
     * @param allowedReceiverCompanyIds receivers this account may deliver to; empty means unrestricted
     * @param receivingCompanyOf        the case as this account reads it, read only when the list isn't
     *                                  empty; empty when there is no case in the answer
     */
    public ConnectApiAttachmentsV2Gateway(AttachmentGrantsApi api, ConnectApiSessionStore sessionStore,
                                          Set<Long> allowedReceiverCompanyIds,
                                          Function<String, Optional<CaseSide>> receivingCompanyOf) {
        this(api, sessionStore, new UploadCoordinator(), Duration.ofSeconds(1), allowedReceiverCompanyIds,
            receivingCompanyOf);
    }

    ConnectApiAttachmentsV2Gateway(AttachmentGrantsApi api, ConnectApiSessionStore sessionStore,
                                   UploadCoordinator coordinator, Duration completeBackoff) {
        this(api, sessionStore, coordinator, completeBackoff, Set.of(), caseToken -> Optional.empty());
    }

    ConnectApiAttachmentsV2Gateway(AttachmentGrantsApi api, ConnectApiSessionStore sessionStore,
                                   UploadCoordinator coordinator, Duration completeBackoff,
                                   Set<Long> allowedReceiverCompanyIds,
                                   Function<String, Optional<CaseSide>> receivingCompanyOf) {
        this.api = api;
        this.sessionStore = sessionStore;
        this.coordinator = coordinator;
        this.completeBackoff = completeBackoff;
        this.allowedReceiverCompanyIds = allowedReceiverCompanyIds == null ? Set.of() : Set.copyOf(allowedReceiverCompanyIds);
        if (!this.allowedReceiverCompanyIds.isEmpty() && receivingCompanyOf == null) {
            throw new IllegalArgumentException("a receiver allowlist needs a way to read the case's receiving company");
        }
        this.receivingCompanyOf = receivingCompanyOf;
    }

    @Override
    public AttachmentGrant createGrant(String caseToken, String fileName, long expectedSizeBytes) {
        requireCase(caseToken);
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (expectedSizeBytes < 1) {
            throw new IllegalArgumentException("expectedSizeBytes must be at least 1, got " + expectedSizeBytes);
        }
        requireReceiverAllowed(caseToken);
        AttachmentGrantCreateRequestDTO request = new AttachmentGrantCreateRequestDTO()
            .fileName(fileName)
            .expectedSizeBytes(expectedSizeBytes);
        try {
            return toGrant(call("create grant", () -> api.createAttachmentGrant(caseToken, request)));
        } catch (AttachmentV2Exception e) {
            throw receiverNotAllowedOr(e);
        }
    }

    /**
     * Defense in depth for the sender's receiver allowlist (the server enforces its own): with a
     * non-empty list, the case's receiving company is read and checked before any grant request.
     * Only the case's sender can create a grant and its receiver is always the case's receiving
     * company, so this one check covers {@link #createGrant} and {@link #send}. A case this
     * account receives ({@code INBOUND}) isn't checked: it isn't a delivery from this account, and
     * the server's sender check answers it as {@code attachment/forbidden}, as it does without a
     * list. An empty list is unrestricted and reads nothing.
     */
    private void requireReceiverAllowed(String caseToken) {
        if (allowedReceiverCompanyIds.isEmpty()) {
            return;
        }
        Optional<CaseSide> side = call("read the case's receiving company", () -> receivingCompanyOf.apply(caseToken));
        if (side.isPresent() && side.get().inbound()) {
            return;
        }
        Long receiver = side.map(CaseSide::receiveCompanyId).orElse(null);
        if (receiver == null) {
            throw new AttachmentV2Exception("the case's receiving company is unknown, so the receiver allowlist can't be"
                + " checked", 0, AttachmentV2Exception.CLIENT_PRECONDITION);
        }
        if (!allowedReceiverCompanyIds.contains(receiver)) {
            throw new AttachmentV2Exception("receiver company " + receiver + " is not on this account's receiver"
                + " allowlist", 0, AttachmentV2Exception.RECEIVER_NOT_ALLOWED);
        }
    }

    /**
     * Grant creation only: the server's allowlist refusal reads the same as this client's, whether
     * the answer came through the library's error handler or a plain RestTemplate.
     */
    private static AttachmentV2Exception receiverNotAllowedOr(AttachmentV2Exception e) {
        if (e.status() == 403 && ConnectApiErrors.isProblem(e.getCause(), RECEIVER_NOT_ALLOWED_TYPE)) {
            return new AttachmentV2Exception(e.getMessage(), 403, AttachmentV2Exception.RECEIVER_NOT_ALLOWED,
                e.getCause());
        }
        return e;
    }

    @Override
    public AttachmentGrant getGrant(String caseToken, long grantId) {
        requireCase(caseToken);
        return toGrant(call("get grant", () -> api.getAttachmentGrant(caseToken, grantId)));
    }

    @Override
    public AttachmentGrantPage listGrants(String caseToken, int page, int size) {
        requireCase(caseToken);
        AttachmentGrantPageDTO dto = call("list grants", () -> api.listAttachmentGrants(caseToken, page, size));
        if (dto == null) {
            throw emptyAnswer("list grants");
        }
        List<AttachmentGrant> content = dto.getContent() == null ? List.of()
            : dto.getContent().stream().map(ConnectApiAttachmentsV2Gateway::toGrant).toList();
        return new AttachmentGrantPage(content, orZero(dto.getTotalElements()), orZero(dto.getTotalPages()),
            orZero(dto.getSize()), orZero(dto.getNumber()));
    }

    @Override
    public UploadLink singleUploadLink(String caseToken, long grantId) {
        requireCase(caseToken);
        SingleUploadUrlDTO dto = call("sign single upload link", () -> api.signSingleUploadUrl(caseToken, grantId));
        if (dto == null) {
            throw emptyAnswer("sign single upload link");
        }
        return new UploadLink(1, required(dto.getUrl(), "url"), dto.getHeaders(), null, dto.getExpiresAt());
    }

    @Override
    public List<UploadLink> s3PartLinks(String caseToken, long grantId, List<Integer> partNumbers) {
        requireCase(caseToken);
        requireNumbers(partNumbers);
        S3PartSignRequestDTO request = new S3PartSignRequestDTO().partNumbers(List.copyOf(partNumbers));
        S3PartsDTO dto = call("sign S3 part links", () -> api.signS3Parts(caseToken, grantId, request));
        if (dto == null || dto.getParts() == null) {
            throw emptyAnswer("sign S3 part links");
        }
        return dto.getParts().stream().map(ConnectApiAttachmentsV2Gateway::toLink).toList();
    }

    @Override
    public List<UploadLink> azureBlockLinks(String caseToken, long grantId, List<Integer> blockNumbers) {
        requireCase(caseToken);
        requireNumbers(blockNumbers);
        AzureBlockSignRequestDTO request = new AzureBlockSignRequestDTO().blockNumbers(List.copyOf(blockNumbers));
        AzureBlocksDTO dto = call("sign Azure block links", () -> api.signAzureBlocks(caseToken, grantId, request));
        if (dto == null || dto.getBlocks() == null) {
            throw emptyAnswer("sign Azure block links");
        }
        return dto.getBlocks().stream().map(ConnectApiAttachmentsV2Gateway::toLink).toList();
    }

    @Override
    public AttachmentGrant completeSingle(String caseToken, long grantId) {
        requireCase(caseToken);
        return toGrant(call("complete single upload", () -> api.completeSingleUpload(caseToken, grantId)));
    }

    @Override
    public AttachmentGrant completeS3Multipart(String caseToken, long grantId, List<UploadReceipts.PartReceipt> receipts) {
        requireCase(caseToken);
        if (receipts == null || receipts.isEmpty()) {
            throw new IllegalArgumentException("receipts must cover every part of the plan");
        }
        S3MultipartCompletionRequestDTO request = new S3MultipartCompletionRequestDTO().parts(receipts.stream()
            .map(r -> new S3MultipartPartReceiptDTO().partNumber(r.partNumber()).etag(r.etag()))
            .toList());
        return toGrant(call("complete S3 multipart upload", () -> api.completeS3Multipart(caseToken, grantId, request)));
    }

    @Override
    public AttachmentGrant completeAzureBlock(String caseToken, long grantId) {
        requireCase(caseToken);
        return toGrant(call("complete Azure block upload", () -> api.completeAzureBlockUpload(caseToken, grantId)));
    }

    @Override
    public AttachmentGrant complete(String caseToken, AttachmentGrant grant, UploadReceipts receipts) {
        if (grant == null) {
            throw new IllegalArgumentException("grant must not be null");
        }
        requireSupportedMode(grant);
        return switch (grant.mode()) {
            case SINGLE -> completeSingle(caseToken, grant.grantId());
            case S3_MULTIPART -> completeS3Multipart(caseToken, grant.grantId(), receipts == null ? null : receipts.parts());
            case AZURE_BLOCK -> completeAzureBlock(caseToken, grant.grantId());
            case GCS_RESUMABLE -> throw unsupported(grant);
        };
    }

    @Override
    public AttachmentGrant abandon(String caseToken, long grantId) {
        requireCase(caseToken);
        return toGrant(call("abandon grant", () -> api.abandonAttachmentGrant(caseToken, grantId)));
    }

    @Override
    public UploadReceipts upload(String caseToken, AttachmentGrant grant, Path file, UploadProgressListener listener) {
        requireCase(caseToken);
        if (grant == null) {
            throw new IllegalArgumentException("grant must not be null");
        }
        requireSupportedMode(grant);
        requireRegularFile(file);
        return coordinator.upload(grant, file, linkSource(caseToken, grant), listener);
    }

    @Override
    public AttachmentGrant send(String caseToken, Path file, UploadProgressListener listener) {
        requireCase(caseToken);
        requireRegularFile(file);
        AttachmentGrant grant = createGrant(caseToken, file.getFileName().toString(), sizeOf(file));
        UploadReceipts receipts;
        try {
            receipts = coordinator.upload(grant, file, linkSource(caseToken, grant), listener);
        } catch (RuntimeException uploadFailure) {
            abandonUnlessTerminal(caseToken, grant.grantId(), uploadFailure);
            throw uploadFailure;
        }
        try {
            return completeWithRetry(caseToken, grant, receipts);
        } catch (RuntimeException completeFailure) {
            AttachmentGrant completed = recoverAfterFailedComplete(caseToken, grant.grantId(), completeFailure);
            if (completed != null) {
                return completed;
            }
            throw completeFailure;
        }
    }

    /** The link calls for the grant's mode, bound to this case and grant. */
    private UploadCoordinator.LinkSource linkSource(String caseToken, AttachmentGrant grant) {
        long grantId = grant.grantId();
        return switch (grant.mode()) {
            case SINGLE -> numbers -> List.of(singleUploadLink(caseToken, grantId));
            case S3_MULTIPART -> numbers -> s3PartLinks(caseToken, grantId, numbers);
            case AZURE_BLOCK -> numbers -> azureBlockLinks(caseToken, grantId, numbers);
            case GCS_RESUMABLE -> numbers -> {
                throw unsupported(grant);
            };
        };
    }

    // PROVISIONAL(tsanetgit/Connect-API-Code#182): the platform has GCS turned off, so the client
    // refuses gcsResumable grants here, in unsupported() and in the GCS_RESUMABLE arms of
    // complete() and linkSource(). Once GCS is on, these give way to support for the mode (the
    // same refusal is in UploadCoordinator.upload).
    private static void requireSupportedMode(AttachmentGrant grant) {
        if (grant.mode() == null || grant.mode() == UploadMode.GCS_RESUMABLE) {
            throw unsupported(grant);
        }
    }

    private static AttachmentV2Exception unsupported(AttachmentGrant grant) {
        return new AttachmentV2Exception("upload mode " + (grant.mode() == null ? "(none)" : grant.mode().value())
            + " is not supported by this client", 0, AttachmentV2Exception.UNSUPPORTED_UPLOAD_MODE);
    }

    /**
     * Complete is safe to repeat: completing a completed grant returns it unchanged. An
     * interrupted thread sends no complete, and an interrupt during a retry keeps the failure
     * being retried as suppressed.
     */
    private AttachmentGrant completeWithRetry(String caseToken, AttachmentGrant grant, UploadReceipts receipts) {
        AttachmentV2Exception retrying = null;
        for (int attempt = 1; ; attempt++) {
            ConnectApiErrors.requireNotInterrupted("before completing grant " + grant.grantId(), retrying);
            try {
                return complete(caseToken, grant, receipts);
            } catch (AttachmentV2Exception e) {
                boolean retryable = e.is(AttachmentV2Exception.CONNECTIVITY) || e.status() / 100 == 5;
                if (!retryable || attempt >= COMPLETE_ATTEMPTS) {
                    throw e;
                }
                ConnectApiErrors.pause(completeBackoff.multipliedBy(attempt), "while retrying complete", e);
                retrying = e;
            }
        }
    }

    /**
     * Best effort; the original failure stays primary and keeps the abandon failure as
     * suppressed. A 409 means the grant is already terminal: there is nothing left to abandon.
     * An interrupted thread makes no network call; the grant expires on the platform.
     */
    private void abandonUnlessTerminal(String caseToken, long grantId, RuntimeException primary) {
        if (isTerminal(primary) || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            abandon(caseToken, grantId);
        } catch (RuntimeException abandonFailure) {
            primary.addSuppressed(abandonFailure);
        }
    }

    /**
     * Settles a grant whose complete failed. When every complete lost its answer, one of them
     * may still have landed, which is a delivery, not a failure. Returns the grant when it reads
     * completed, otherwise null; the original failure stays primary and keeps every recovery
     * failure as suppressed.
     *
     * <ul>
     *   <li>An interrupted thread makes no network call.</li>
     *   <li>A 409 from complete: the grant is already terminal and complete did not land.</li>
     *   <li>An abandon that answered: the grant is abandoned, so the file was not delivered.</li>
     *   <li>An abandon that failed, a 409 or a lost answer alike, settles nothing: the grant is
     *       read once.</li>
     * </ul>
     */
    private AttachmentGrant recoverAfterFailedComplete(String caseToken, long grantId, RuntimeException primary) {
        if (isTerminal(primary) || Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            abandon(caseToken, grantId);
            return null;
        } catch (RuntimeException abandonFailure) {
            primary.addSuppressed(abandonFailure);
        }
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        try {
            AttachmentGrant current = getGrant(caseToken, grantId);
            return current.completed() ? current : null;
        } catch (RuntimeException readFailure) {
            primary.addSuppressed(readFailure);
            return null;
        }
    }

    private static boolean isTerminal(RuntimeException failure) {
        return failure instanceof AttachmentV2Exception e && e.is(AttachmentV2Exception.GRANT_TERMINAL);
    }

    private static <T> T call(String operation, Supplier<T> request) {
        return ConnectApiErrors.call(operation, request);
    }

    static AttachmentGrant toGrant(AttachmentGrantDTO dto) {
        if (dto == null) {
            throw emptyAnswer("grant call");
        }
        UploadMode mode = toMode(dto.getMode());
        AttachmentGrant.UploadPlan plan = switch (mode) {
            case S3_MULTIPART -> toPlan(dto.getS3Multipart());
            case AZURE_BLOCK -> toPlan(dto.getAzureBlock());
            case SINGLE, GCS_RESUMABLE -> null;
        };
        return new AttachmentGrant(
            required(dto.getGrantId(), "grantId"),
            toStatus(dto.getStatus()),
            dto.getFileName(),
            required(dto.getExpectedSizeBytes(), "expectedSizeBytes"),
            dto.getCreatedAt(),
            dto.getExpiresAt(),
            mode,
            plan
        );
    }

    // Exhaustive switches over the generated enums: a value added to the spec fails this build.
    private static AttachmentGrant.Status toStatus(com.tsanet.api.generated.model.AttachmentGrantStatus status) {
        if (status == null) {
            throw emptyAnswer("grant status");
        }
        return switch (status) {
            case OPEN -> AttachmentGrant.Status.OPEN;
            case COMPLETED -> AttachmentGrant.Status.COMPLETED;
            case ABANDONED -> AttachmentGrant.Status.ABANDONED;
            case EXPIRED -> AttachmentGrant.Status.EXPIRED;
        };
    }

    private static UploadMode toMode(com.tsanet.api.generated.model.AttachmentUploadMode mode) {
        if (mode == null) {
            throw emptyAnswer("grant mode");
        }
        return switch (mode) {
            case SINGLE -> UploadMode.SINGLE;
            case S3_MULTIPART -> UploadMode.S3_MULTIPART;
            case AZURE_BLOCK -> UploadMode.AZURE_BLOCK;
            case GCS_RESUMABLE -> UploadMode.GCS_RESUMABLE;
        };
    }

    // A plan without its counts maps to no plan, which the coordinator refuses as unusable.
    private static AttachmentGrant.UploadPlan toPlan(S3MultipartPlanDTO plan) {
        return plan == null || plan.getTotalParts() == null || plan.getPartSizeBytes() == null ? null
            : new AttachmentGrant.UploadPlan(plan.getTotalParts(), plan.getPartSizeBytes());
    }

    private static AttachmentGrant.UploadPlan toPlan(AzureBlockPlanDTO plan) {
        return plan == null || plan.getTotalBlocks() == null || plan.getBlockSizeBytes() == null ? null
            : new AttachmentGrant.UploadPlan(plan.getTotalBlocks(), plan.getBlockSizeBytes());
    }

    private static UploadLink toLink(S3PartDTO part) {
        return new UploadLink(required(part.getPartNumber(), "partNumber"), required(part.getUrl(), "url"),
            part.getHeaders(), part.getSizeBytes(), part.getExpiresAt());
    }

    private static UploadLink toLink(AzureBlockDTO block) {
        return new UploadLink(required(block.getBlockNumber(), "blockNumber"), required(block.getUrl(), "url"),
            block.getHeaders(), block.getSizeBytes(), block.getExpiresAt());
    }

    private static <T> T required(T value, String field) {
        return ConnectApiErrors.required(value, field);
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static AttachmentV2Exception emptyAnswer(String operation) {
        return ConnectApiErrors.emptyAnswer(operation);
    }

    private static void requireNumbers(List<Integer> numbers) {
        if (numbers == null || numbers.isEmpty() || numbers.size() > UploadCoordinator.MAX_LINKS_PER_CALL) {
            throw new IllegalArgumentException("ask for between 1 and " + UploadCoordinator.MAX_LINKS_PER_CALL
                + " numbers per call");
        }
    }

    private void requireCase(String caseToken) {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
        if (caseToken == null || caseToken.isBlank()) {
            throw new IllegalArgumentException("caseToken must not be blank");
        }
    }

    private static void requireRegularFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("file must be an existing regular file");
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new AttachmentV2Exception("cannot read the file to send: " + e.getClass().getSimpleName(), 0,
                AttachmentV2Exception.CLIENT_PRECONDITION, e);
        }
    }
}

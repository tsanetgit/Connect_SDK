package com.tsanet.api.connectapi.internal;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadProgressListener;
import com.tsanet.api.attachments.v2.UploadReceipts;
import com.tsanet.api.facade.AttachmentsV2Facade;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * {@link AttachmentsV2Facade} over the {@link AttachmentsV2Api} seam and the
 * {@link AttachmentUploadExecutor}. Owns the flow rules of the contract
 * (tsanetgit/Connect-API-Code#147): abandon on any upload failure, one re-grant on a
 * complete-time {@code grant-expired}, complete retried on transient failure because it is
 * idempotent on the grant id, and the platform's recorded outcome returned unchanged.
 */
public class ConnectApiAttachmentsV2Gateway implements AttachmentsV2Facade {

    static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    private static final int COMPLETE_ATTEMPTS = 3;

    private final AttachmentsV2Api api;
    private final ConnectApiSessionStore sessionStore;
    private final AttachmentUploadExecutor executor;
    private final Duration completeBackoff;

    public ConnectApiAttachmentsV2Gateway(AttachmentsV2Api api, ConnectApiSessionStore sessionStore) {
        this(api, sessionStore, new AttachmentUploadExecutor(), Duration.ofSeconds(1));
    }

    ConnectApiAttachmentsV2Gateway(AttachmentsV2Api api, ConnectApiSessionStore sessionStore,
                                   AttachmentUploadExecutor executor, Duration completeBackoff) {
        this.api = api;
        this.sessionStore = sessionStore;
        this.executor = executor;
        this.completeBackoff = completeBackoff;
    }

    @Override
    public AttachmentGrant grant(String caseToken, AttachmentGrantRequest request) {
        requireLogin();
        requireToken(caseToken);
        return api.createGrant(caseToken, request, UUID.randomUUID().toString());
    }

    @Override
    public UploadReceipts upload(AttachmentGrant grant, Path file, UploadProgressListener listener) {
        if (grant == null) {
            throw new IllegalArgumentException("grant must not be null");
        }
        requireRegularFile(file);
        return executor.execute(grant, file, listener);
    }

    @Override
    public AttachmentCompleteResult complete(String caseToken, UUID grantId, AttachmentCompleteRequest request) {
        requireLogin();
        requireToken(caseToken);
        return completeWithRetry(caseToken, grantId, request);
    }

    @Override
    public void abandon(String caseToken, UUID grantId) {
        requireLogin();
        requireToken(caseToken);
        api.abandon(caseToken, grantId);
    }

    @Override
    public AttachmentCompleteResult send(String caseToken, Path file, String contentType, String description,
                                         boolean withSha256, UploadProgressListener listener) {
        requireLogin();
        requireToken(caseToken);
        requireRegularFile(file);
        long size = sizeOf(file);
        String sha256 = withSha256 ? sha256Of(file) : null;
        String type = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType.strip();
        String text = description == null || description.isBlank() ? null : description.strip();
        AttachmentGrantRequest request =
            new AttachmentGrantRequest(file.getFileName().toString(), type, size, sha256, text);

        AttachmentGrant grant = api.createGrant(caseToken, request, UUID.randomUUID().toString());
        UploadReceipts receipts = uploadOrAbandon(caseToken, grant, file, listener);
        try {
            return completeWithRetry(caseToken, grant.grantId(), completeRequest(receipts, sha256));
        } catch (AttachmentV2Exception e) {
            if (!e.isProblem(AttachmentV2Exception.GRANT_EXPIRED)) {
                throw e;
            }
        }
        // The grant expired between upload and complete: one fresh grant, one more upload.
        AttachmentGrant fresh = api.createGrant(caseToken, request, UUID.randomUUID().toString());
        UploadReceipts again = uploadOrAbandon(caseToken, fresh, file, listener);
        return completeWithRetry(caseToken, fresh.grantId(), completeRequest(again, sha256));
    }

    private UploadReceipts uploadOrAbandon(String caseToken, AttachmentGrant grant, Path file,
                                           UploadProgressListener listener) {
        try {
            return executor.execute(grant, file, listener);
        } catch (RuntimeException uploadFailure) {
            // Best effort, and the upload failure stays primary: nothing may become visible.
            try {
                api.abandon(caseToken, grant.grantId());
            } catch (RuntimeException abandonFailure) {
                uploadFailure.addSuppressed(abandonFailure);
            }
            throw uploadFailure;
        }
    }

    private AttachmentCompleteResult completeWithRetry(String caseToken, UUID grantId, AttachmentCompleteRequest request) {
        AttachmentV2Exception last = null;
        for (int attempt = 1; attempt <= COMPLETE_ATTEMPTS; attempt++) {
            try {
                return api.complete(caseToken, grantId, request);
            } catch (AttachmentV2Exception e) {
                boolean transientFailure = e.status() == 0 || e.status() / 100 == 5;
                if (!transientFailure || attempt == COMPLETE_ATTEMPTS) {
                    throw e;
                }
                last = e;
                pause(completeBackoff.multipliedBy(attempt));
            }
        }
        throw last;
    }

    private static void pause(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentV2Exception("interrupted while retrying complete", 0,
                AttachmentV2Exception.CONNECTIVITY, e);
        }
    }

    private static AttachmentCompleteRequest completeRequest(UploadReceipts receipts, String sha256) {
        return new AttachmentCompleteRequest(receipts.bytesSent(), sha256, receipts.parts());
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }

    private static void requireToken(String caseToken) {
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

    /** One streaming pass of its own, never inside a retried upload write. */
    static String sha256Of(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[AttachmentUploadExecutor.READ_BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new AttachmentV2Exception("cannot digest the file to send: " + e.getClass().getSimpleName(), 0,
                AttachmentV2Exception.CLIENT_PRECONDITION, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

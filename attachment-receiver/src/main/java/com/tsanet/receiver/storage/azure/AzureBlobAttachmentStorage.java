package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link AttachmentStorage} on one Azure Blob Storage container (block blobs): the
 * container-scoped sibling of {@link AzureFilesAttachmentStorage}. Blob is the default
 * Azure option for member self-service; Files stays for members who need a share
 * (tsanetgit/Connect_SDK#71). The client is injected: account, container and credential
 * are wiring concerns, not this class's.
 *
 * <p>Blob names are {@code [prefix/]encode(caseNumber)/encode(fileName)}, both components
 * passed through the same {@link AzureFileNames} encoder the Files adapter uses. Blob
 * Storage is documented as a flat namespace, but the live acceptance run proved that the
 * request path is normalized on the way in: {@code ../../etc/passwd} landed outside the
 * case prefix, a trailing dot was stripped, and a backslash became a separator. Store
 * and exists agreed on every one of them, so the SPI contract held, but a remote-controlled
 * name escaping the tenant prefix would break multi-tenant isolation in the hosted mode.
 * Encoding removes the possibility rather than guarding against it: {@code /}, {@code \},
 * {@code %}, leading and trailing dots are all percent-encoded, so no name can traverse
 * and no name can start with a dot (which keeps the {@code .verify-} probe namespace
 * collision-free). {@link #exists} builds names through the same method, so store and
 * check can never disagree. The encoder's 255-character component cap is stricter than
 * Blob's 1,024-character name limit; an over-long name fails fast through the SPI's
 * checked exception.
 *
 * <p>Streaming: bytes are read in {@value #BLOCK_SIZE}-byte buffers. A file that ends
 * inside the first buffer is written with a single {@code Put Blob}, one atomic request.
 * Anything larger is staged as blocks ({@code Put Block}) and committed with one
 * {@code Put Block List}. Two Blob Storage facts make the SPI's no-partial-visibility rule
 * hold structurally:
 * <ul>
 *   <li><b>Uncommitted blocks are invisible.</b> A blob that has only received staged
 *       blocks reads as {@code BlobNotFound}, and the service garbage-collects it seven days
 *       after the last {@code Put Block}. On any mid-stream failure the upload is
 *       abandoned, never committed — committing a partial block list would publish a
 *       truncated object — and there is deliberately no cleanup round-trip that could
 *       itself fail (the GCS adapter's shape, unlike S3's abort call).</li>
 *   <li><b>Ambiguous commit, resolved exactly.</b> A {@code Put Block List} that fails
 *       client-side may have committed server-side. As in the S3 and GCS adapters since
 *       tsanetgit/Connect_SDK#69, the resolution is identity, not size. Here the block ids
 *       carry a UUID minted for this store call: the blob's committed block list
 *       ({@code Get Block List}) equal to the ids this call staged means this commit
 *       landed, and nothing else can produce that list. Any other shape throws.</li>
 * </ul>
 * Block ids are fixed-length, as the service requires: one UUID per store call plus a
 * zero-padded index. With {@value #BLOCK_SIZE}-byte blocks the service's 50,000-block
 * ceiling is about 390 GiB, far above any platform cap. Same-name behavior is overwrite
 * (proven live for both the single-request and the staged path), consistent with the
 * other adapters while the policy stays implementation-defined upstream
 * (tsanetgit/Connect-API-Code#140, question 2).
 *
 * <p><b>Go-live probe.</b> {@link #verifyAccess} stages one uncommitted block on a
 * {@code .verify-<uuid>} name (write), then reads that name's properties (read); the answer,
 * absent, is ignored, since the probe tests permission, not state. Nothing is committed and nothing needs deleting, so it replaces
 * the other adapters' write-read-delete sentinel while validating the same two rights the
 * adapter needs at runtime: write for {@link #store}, read for {@link #exists} and the
 * ambiguous-commit resolution. Required rights are therefore SAS {@code rw} (or
 * {@code rcw}) on the container, or the Storage Blob Data Contributor role for an Entra
 * identity. A SAS carrying only {@code cw} fails go-live at the read stage
 * (tsanetgit/Connect_SDK#73; before it, such a SAS passed go-live and failed on the first
 * {@code exists}).
 *
 * <p>Failure classification reads the service error code before the HTTP status, because
 * Azure Storage answers {@code AuthenticationFailed} with 403, not 401.
 */
public final class AzureBlobAttachmentStorage implements AttachmentStorage {

    /** Stream read granularity and staged block size: 8 MiB (the service allows up to 4,000 MiB). */
    static final int BLOCK_SIZE = 8 * 1024 * 1024;

    static final String VERIFY_PREFIX = ".verify-";

    private final AzureBlobContainer container;
    private final String containerName;
    private final String prefix;

    /** Production entry point: wraps the injected client in the logic-free SDK seam. */
    public static AzureBlobAttachmentStorage forContainer(BlobContainerClient client, String prefix) {
        return new AzureBlobAttachmentStorage(new SdkAzureBlobContainer(client),
                client.getBlobContainerName(), prefix);
    }

    AzureBlobAttachmentStorage(AzureBlobContainer container, String containerName, String prefix) {
        this.container = Objects.requireNonNull(container, "container");
        this.containerName = Objects.requireNonNull(containerName, "containerName");
        if (containerName.isBlank()) {
            throw new IllegalArgumentException("containerName must not be blank");
        }
        this.prefix = normalize(prefix);
    }

    @Override
    public StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException {
        String name = nameOrThrow(attachment.caseNumber(), attachment.fileName());
        byte[] buffer = new byte[BLOCK_SIZE];
        int firstLength = fill(content, buffer, name);
        if (firstLength < BLOCK_SIZE) {
            // EOF inside the first buffer: one atomic Put Blob, no partial-visibility window.
            try {
                container.putBlob(name, attachment.contentType(), Arrays.copyOf(buffer, firstLength));
            } catch (RuntimeException e) {
                throw wrap("put blob", name, e);
            }
            return new StoredAttachment(name, firstLength);
        }
        return staged(attachment, content, name, buffer);
    }

    private StoredAttachment staged(IncomingAttachment attachment, InputStream content,
                                    String name, byte[] buffer)
            throws AttachmentStorageException {
        String uploadId = UUID.randomUUID().toString();
        List<String> blockIds = new ArrayList<>();
        long total = 0;
        // A stream failure inside fill() propagates as the AttachmentStorageException it
        // already is (name and phase included); abandoning is the same no-op either way.
        try {
            int length = BLOCK_SIZE; // the first buffer arrived full, or we would not be here
            boolean last = false;
            while (!last) {
                if (!blockIds.isEmpty()) {
                    // The one buffer is reused: stageBlock is synchronous, so the SDK has
                    // finished with it before the next fill overwrites it.
                    length = fill(content, buffer, name);
                    if (length == 0) {
                        break; // EOF landed exactly on the previous block's boundary.
                    }
                }
                String blockId = blockId(uploadId, blockIds.size());
                container.stageBlock(name, blockId, buffer, length);
                blockIds.add(blockId);
                total += length;
                last = length < BLOCK_SIZE;
            }
        } catch (RuntimeException stageFailure) {
            // Abandon: nothing was committed, so nothing is visible; the blocks expire.
            throw wrap("stage block", name, stageFailure);
        }

        try {
            container.commitBlockList(name, blockIds, attachment.contentType());
        } catch (RuntimeException commitFailure) {
            return resolveAmbiguousCommit(name, blockIds, total, commitFailure);
        }
        return new StoredAttachment(name, total);
    }

    /**
     * A failed commit is ambiguous: the service may have committed. The committed block
     * list equal to the ids this call staged means exactly that — no other writer holds
     * this call's UUID — so report the commit as the success it was. A different list
     * (a previous blob of the same name, another writer's, or nothing at all) throws.
     */
    private StoredAttachment resolveAmbiguousCommit(String name, List<String> blockIds, long total,
                                                    RuntimeException commitFailure)
            throws AttachmentStorageException {
        try {
            if (blockIds.equals(container.committedBlockIdsOrAbsent(name))) {
                return new StoredAttachment(name, total);
            }
        } catch (RuntimeException probeFailure) {
            commitFailure.addSuppressed(probeFailure);
        }
        throw wrap("commit", name, commitFailure);
    }

    @Override
    public boolean exists(String caseNumber, String fileName) throws AttachmentStorageException {
        String name = nameOrThrow(caseNumber, fileName);
        try {
            return container.sizeOrAbsent(name) >= 0;
        } catch (RuntimeException e) {
            throw wrap("exists check", name, e);
        }
    }

    @Override
    public void verifyAccess() throws AttachmentStorageException {
        String probeName = (prefix.isEmpty() ? "" : prefix + "/") + VERIFY_PREFIX + UUID.randomUUID();
        byte[] probe = "attachment-receiver go-live probe".getBytes(StandardCharsets.UTF_8);
        try {
            // One uncommitted block: proves write permission, commits nothing, and the
            // service reclaims it in seven days. Nothing to delete.
            container.stageBlock(probeName, blockId(UUID.randomUUID().toString(), 0), probe, probe.length);
        } catch (RuntimeException e) {
            throw new AttachmentStorageException(classify("write", e), e);
        }
        try {
            // Get Blob Properties on the same name proves read permission. The answer is
            // "absent" (only uncommitted blocks exist) and is deliberately ignored: the
            // probe tests permission, not state.
            container.sizeOrAbsent(probeName);
        } catch (RuntimeException e) {
            throw new AttachmentStorageException(classify("read", e), e);
        }
    }

    /** Wrong-credential, no-permission, and wrong-target must read differently. */
    private String classify(String stage, RuntimeException e) {
        if (e instanceof BlobStorageException bse) {
            BlobErrorCode code = bse.getErrorCode();
            int status = bse.getStatusCode();
            if (BlobErrorCode.AUTHENTICATION_FAILED.equals(code)
                    || BlobErrorCode.INVALID_AUTHENTICATION_INFO.equals(code)
                    || BlobErrorCode.NO_AUTHENTICATION_INFORMATION.equals(code)) {
                return "wrong credential: the container rejected the identity (verify " + stage + ", " + code + ")";
            }
            if (BlobErrorCode.AUTHORIZATION_FAILURE.equals(code)
                    || BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH.equals(code)
                    || BlobErrorCode.INSUFFICIENT_ACCOUNT_PERMISSIONS.equals(code)
                    || status == 403) {
                return "no permission: " + stage + " denied on container '" + containerName + "'"
                        + (code == null ? "" : " (" + code + ")");
            }
            if (BlobErrorCode.CONTAINER_NOT_FOUND.equals(code) || status == 404) {
                return "wrong target: container '" + containerName
                        + "' (or its account) does not exist (verify " + stage + ")";
            }
            return "verify " + stage + " failed on container '" + containerName + "': " + code
                    + " (HTTP " + status + ")";
        }
        return "connectivity: cannot reach the container (verify " + stage + "): " + e.getMessage();
    }

    private int fill(InputStream content, byte[] buffer, String name)
            throws AttachmentStorageException {
        try {
            // readNBytes loops over short reads; < buffer.length only ever means EOF.
            return content.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "stream failed mid-read for " + name + "; nothing committed", e);
        }
    }

    /**
     * Fixed length by construction for every index below 100,000 (36-char UUID, dash,
     * five digits: 42 bytes before base64, under the service's 64-byte cap), which is
     * above the service's own 50,000-block ceiling. One UUID per store call, so a retried
     * store of the same name never commits another attempt's blocks and the ambiguous-commit
     * resolution can recognize this call's commit by identity.
     */
    static String blockId(String uploadId, int index) {
        return Base64.getEncoder().encodeToString(
                String.format("%s-%05d", uploadId, index).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A hostile or oversized name is remote-controlled input; the encoder's
     * {@link IllegalArgumentException} must surface through the SPI's checked exception,
     * never as a raw unchecked escape.
     */
    private String nameOrThrow(String caseNumber, String fileName) throws AttachmentStorageException {
        try {
            String base = AzureFileNames.encode(caseNumber) + "/" + AzureFileNames.encode(fileName);
            return prefix.isEmpty() ? base : prefix + "/" + base;
        } catch (IllegalArgumentException e) {
            throw new AttachmentStorageException("invalid attachment name: " + e.getMessage(), e);
        }
    }

    private AttachmentStorageException wrap(String operation, String name, Exception e) {
        return new AttachmentStorageException(
                operation + " failed for blob " + containerName + "/" + name + ": " + e.getMessage(), e);
    }

    private static String normalize(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import com.azure.storage.file.share.ShareClient;
import com.azure.storage.file.share.models.ShareErrorCode;
import com.azure.storage.file.share.models.ShareStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link AttachmentStorage} on an Azure file share. Azure Files gives neither of the
 * invariants S3 gives structurally, so this adapter builds both:
 *
 * <ul>
 *   <li><b>No partial visibility:</b> an Azure file is visible from {@code create}, so
 *       bytes stream into a temp file ({@code .incoming-<uuid>} in the destination
 *       directory) and reach the final name only by rename-with-replace. A failed store
 *       best-effort deletes its temp; the final name is never touched.</li>
 *   <li><b>Traversal-inert names:</b> the share has real directory semantics and
 *       Windows naming rules, so both path components are encoded via
 *       {@link AzureFileNames}: remote layout is
 *       {@code [prefix/]encode(caseNumber)/encode(fileName)}. Store and exists share
 *       the encoder, so they can never disagree. Encoded names can never start with a
 *       dot, which is what keeps the temp namespace collision-free.</li>
 * </ul>
 *
 * <p>Unknown-length streaming: the file is created at size 0 and grown per 4 MiB chunk
 * (resize, then range write) — Azure Files has no append primitive and caps a range
 * write at 4 MiB.
 *
 * <p>Deliberate policies, both documented rather than implied: an <b>ambiguous
 * rename</b> (threw client-side, but the final file exists and the temp is gone) is
 * reported as the success it was, mirroring the S3 adapter's ambiguous-complete policy;
 * and <b>same-name behavior is overwrite</b> (rename-with-replace), consistent with the
 * other adapters while the policy stays implementation-defined upstream
 * (tsanetgit/Connect-API-Code#140, question 2). Note that Azure Files is
 * case-insensitive and case-preserving: {@code A.txt} and {@code a.txt} are the same
 * remote file here but distinct objects on S3 — folded into the same open same-name
 * question.
 *
 * <p>Auth is wiring, not adapter code — inject a {@link ShareClient} built for the
 * deployment: a SAS URL ({@code new ShareClientBuilder().endpoint(sasUrl)}), a
 * connection string ({@code .connectionString(cs).shareName(share)}), or OAuth /
 * managed identity ({@code .credential(tokenCredential).shareTokenIntent(BACKUP)}) for
 * the member-deployed mode. Required rights on the share: create, write, read, delete
 * (list is exercised only by the live test's teardown, not by the adapter). Caveat for
 * shares also mounted over SMB: an open handle on a destination file
 * can make rename-with-replace fail with a sharing violation; REST-only access never
 * holds handles.
 */
public final class AzureFilesAttachmentStorage implements AttachmentStorage {

    /** Azure Files' maximum single range write: 4 MiB. */
    static final int CHUNK_SIZE = 4 * 1024 * 1024;

    static final String TEMP_PREFIX = ".incoming-";
    private static final String VERIFY_PREFIX = ".verify-";

    private final AzureShare share;
    private final String prefix;

    /** Production entry point: wraps the injected client in the logic-free SDK seam. */
    public static AzureFilesAttachmentStorage forShare(ShareClient client, String directoryPrefix) {
        return new AzureFilesAttachmentStorage(new SdkAzureShare(client), directoryPrefix);
    }

    AzureFilesAttachmentStorage(AzureShare share, String directoryPrefix) {
        this.share = Objects.requireNonNull(share, "share");
        this.prefix = normalize(directoryPrefix);
    }

    @Override
    public StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException {
        String directory;
        String finalPath;
        try {
            directory = directoryFor(attachment.caseNumber());
            finalPath = directory + "/" + AzureFileNames.encode(attachment.fileName());
        } catch (IllegalArgumentException e) {
            // A hostile or oversized name is remote-controlled input; it must surface
            // through the SPI's checked exception, never as a raw unchecked escape.
            throw new AttachmentStorageException("invalid attachment name: " + e.getMessage(), e);
        }
        String tempPath = directory + "/" + TEMP_PREFIX + UUID.randomUUID();

        try {
            share.ensureDirectory(directory);
        } catch (Exception e) {
            throw wrap("prepare directory", finalPath, e);
        }

        long total = 0;
        boolean tempCreated = false;
        try {
            share.createFile(tempPath, 0);
            tempCreated = true;
            byte[] buffer = new byte[CHUNK_SIZE];
            while (true) {
                int length = fill(content, buffer, finalPath);
                if (length == 0) {
                    break;
                }
                share.resizeFile(tempPath, total + length);
                share.uploadRange(tempPath, total,
                        length == buffer.length ? buffer : Arrays.copyOf(buffer, length));
                total += length;
                if (length < buffer.length) {
                    break;
                }
            }
        } catch (AttachmentStorageException e) {
            if (tempCreated) {
                deleteTempQuietly(tempPath, e);
            }
            throw e;
        } catch (Exception e) {
            AttachmentStorageException wrapped = wrap("store", finalPath, e);
            if (tempCreated) {
                deleteTempQuietly(tempPath, wrapped);
            }
            throw wrapped;
        }

        try {
            share.renameFile(tempPath, finalPath, true);
        } catch (Exception renameFailure) {
            return resolveAmbiguousRename(tempPath, finalPath, total, renameFailure);
        }
        return new StoredAttachment(finalPath, total);
    }

    /**
     * A failed rename is ambiguous: the service may have performed it. Final file
     * present and temp gone means exactly that — report the success it was. (Like the
     * S3 twin, this cannot distinguish a concurrent same-name writer's file.)
     */
    private StoredAttachment resolveAmbiguousRename(String tempPath, String finalPath,
                                                    long total, Exception renameFailure)
            throws AttachmentStorageException {
        try {
            if (share.fileExists(finalPath) && !share.fileExists(tempPath)) {
                return new StoredAttachment(finalPath, total);
            }
        } catch (Exception probeFailure) {
            renameFailure.addSuppressed(probeFailure);
        }
        AttachmentStorageException wrapped = wrap("rename", finalPath, renameFailure);
        deleteTempQuietly(tempPath, wrapped);
        throw wrapped;
    }

    private void deleteTempQuietly(String tempPath, Exception primary) {
        try {
            share.deleteFile(tempPath);
        } catch (Exception cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public boolean exists(String caseNumber, String fileName) throws AttachmentStorageException {
        String path;
        try {
            path = directoryFor(caseNumber) + "/" + AzureFileNames.encode(fileName);
        } catch (IllegalArgumentException e) {
            throw new AttachmentStorageException("invalid attachment name: " + e.getMessage(), e);
        }
        try {
            return share.fileExists(path);
        } catch (Exception e) {
            throw wrap("exists check", path, e);
        }
    }

    @Override
    public void verifyAccess() throws AttachmentStorageException {
        String directory = prefix.isEmpty() ? "" : prefix;
        String sentinel = (directory.isEmpty() ? "" : directory + "/")
                + VERIFY_PREFIX + UUID.randomUUID();
        byte[] probe = "attachment-receiver go-live probe".getBytes();
        String stage = "write";
        boolean written = false;
        try {
            if (!directory.isEmpty()) {
                share.ensureDirectory(directory);
            }
            share.createFile(sentinel, probe.length);
            written = true;
            share.uploadRange(sentinel, 0, probe);
            stage = "read";
            if (!Arrays.equals(probe, share.downloadFile(sentinel))) {
                throw new AttachmentStorageException(
                        "verify read returned different content than was written");
            }
            stage = "delete";
            share.deleteFile(sentinel);
        } catch (Exception e) {
            AttachmentStorageException failure = e instanceof AttachmentStorageException ase
                    ? ase
                    : new AttachmentStorageException(classify(stage, e), e);
            if (written && !"delete".equals(stage)) {
                try {
                    share.deleteFile(sentinel);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /** Wrong-credential, wrong-target, and no-permission must read differently. */
    private String classify(String stage, Exception e) {
        if (e instanceof ShareStorageException sse) {
            ShareErrorCode code = sse.getErrorCode();
            if (ShareErrorCode.AUTHENTICATION_FAILED.equals(code)) {
                return "wrong credential: the share rejected the identity (verify " + stage + ")";
            }
            if (ShareErrorCode.AUTHORIZATION_FAILURE.equals(code)
                    || ShareErrorCode.INSUFFICIENT_ACCOUNT_PERMISSIONS.equals(code)
                    || sse.getStatusCode() == 403) {
                return "no permission: " + stage + " denied on the share";
            }
            if (ShareErrorCode.SHARE_NOT_FOUND.equals(code) || sse.getStatusCode() == 404) {
                return "wrong target: the share (or its account) does not exist (verify " + stage + ")";
            }
            return "verify " + stage + " failed on the share: " + code + " (HTTP "
                    + sse.getStatusCode() + ")";
        }
        return "connectivity: cannot reach the share (verify " + stage + "): " + e.getMessage();
    }

    private int fill(InputStream content, byte[] buffer, String path)
            throws AttachmentStorageException {
        try {
            return content.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "stream failed mid-read for " + path + "; store aborted", e);
        }
    }

    private String directoryFor(String caseNumber) {
        String encoded = AzureFileNames.encode(caseNumber);
        return prefix.isEmpty() ? encoded : prefix + "/" + encoded;
    }

    private AttachmentStorageException wrap(String operation, String path, Exception e) {
        return new AttachmentStorageException(
                operation + " failed for share path " + path + ": " + e.getMessage(), e);
    }

    private static String normalize(String directoryPrefix) {
        if (directoryPrefix == null || directoryPrefix.isBlank()) {
            return "";
        }
        String p = directoryPrefix;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

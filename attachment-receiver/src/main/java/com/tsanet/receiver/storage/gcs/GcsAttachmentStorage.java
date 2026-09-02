package com.tsanet.receiver.storage.gcs;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link AttachmentStorage} on Google Cloud Storage. The client is injected: project,
 * region and credentials are wiring concerns, not this class's.
 *
 * <p>Object keys are {@code [prefix/]caseNumber/fileName}. Per the SPI, {@code fileName} is
 * an opaque token: GCS object names have no traversal semantics, so a name containing
 * {@code /} or {@code ..} merely creates deeper name segments. {@code exists} builds keys
 * through the same method, so store and check can never disagree.
 *
 * <p>Streaming: bytes are read in {@value #BUFFER_SIZE}-byte buffers. A file that ends
 * inside the first buffer is written with a single atomic create; anything larger becomes a
 * resumable upload. A GCS object is not visible until its resumable upload is finished, so
 * the SPI's no-partial-visibility rule holds structurally, and the streamed body is never
 * the caller's stream (each buffer is a fresh copy), so the stream is never closed by this
 * class.
 *
 * <p>Two GCS facts shape the failure paths, and neither has an S3 or Azure twin:
 * <ul>
 *   <li><b>No abort call.</b> A resumable upload that is never finished produces no visible
 *       object and its session expires server-side. On any mid-stream failure the upload is
 *       {@code abandon()}ed, not finished — finishing a partial upload would finalize a
 *       truncated object. There is deliberately no cleanup round-trip that could itself
 *       fail, unlike S3's {@code AbortMultipartUpload}.</li>
 *   <li><b>Ambiguous finish.</b> A {@code finish} that fails client-side may have committed
 *       server-side. When the object is then visible at exactly the byte count written, the
 *       store is treated as committed and reported as success, mirroring the S3 adapter's
 *       ambiguous-complete policy. (Like both twins, this cannot distinguish a concurrent
 *       same-name writer's object.)</li>
 * </ul>
 *
 * <p>Required IAM on the bucket/prefix: {@code storage.objects.create},
 * {@code storage.objects.get} (read metadata for {@code exists} and the ambiguous-finish
 * probe, and read bytes for the verify sentinel), and {@code storage.objects.delete} (the
 * verify sentinel). Unlike S3's HEAD, a missing GCS object reads as a null get rather than
 * a 404 that must be told apart from a permission error, so {@code exists} has no
 * status-ambiguity to guard.
 */
public final class GcsAttachmentStorage implements AttachmentStorage {

    /** Stream read granularity; a 256 KiB multiple, so it is also a valid resumable chunk size. */
    static final int BUFFER_SIZE = 5 * 1024 * 1024;

    private final GcsBucket bucket;
    private final String bucketName;
    private final String prefix;

    /** Production entry point: wraps the injected client in the logic-free SDK seam. */
    public static GcsAttachmentStorage forBucket(Storage storage, String bucketName, String keyPrefix) {
        return new GcsAttachmentStorage(new SdkGcsBucket(storage, bucketName), bucketName, keyPrefix);
    }

    GcsAttachmentStorage(GcsBucket bucket, String bucketName, String keyPrefix) {
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        if (bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.prefix = normalize(keyPrefix);
    }

    @Override
    public StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException {
        String key = key(attachment.caseNumber(), attachment.fileName());
        byte[] first = new byte[BUFFER_SIZE];
        int firstLength = fill(content, first, key);
        if (firstLength < BUFFER_SIZE) {
            // EOF inside the first buffer: one atomic create, no partial-visibility window.
            try {
                bucket.create(key, attachment.contentType(), Arrays.copyOf(first, firstLength));
            } catch (RuntimeException e) {
                throw wrap("create", key, e);
            }
            return new StoredAttachment(key, firstLength);
        }
        return resumable(attachment, content, key, first);
    }

    private StoredAttachment resumable(IncomingAttachment attachment, InputStream content,
                                       String key, byte[] firstBuffer)
            throws AttachmentStorageException {
        GcsBucket.Upload upload;
        try {
            upload = bucket.startResumable(key, attachment.contentType());
        } catch (RuntimeException e) {
            throw wrap("resumable start", key, e);
        }

        long total = 0;
        try {
            byte[] buffer = firstBuffer;
            int length = firstBuffer.length;
            int chunk = 0;
            boolean last = false;
            while (!last) {
                if (chunk > 0) {
                    buffer = new byte[BUFFER_SIZE];
                    length = fill(content, buffer, key);
                    if (length == 0) {
                        break; // EOF landed exactly on the previous chunk's boundary.
                    }
                }
                chunk++;
                upload.write(buffer, length);
                total += length;
                last = length < BUFFER_SIZE;
            }
        } catch (AttachmentStorageException streamFailure) {
            // Already carries the key and phase; re-wrapping would just nest the message.
            upload.abandon();
            throw streamFailure;
        } catch (RuntimeException uploadFailure) {
            upload.abandon();
            throw wrap("store", key, uploadFailure);
        }

        try {
            upload.finish();
        } catch (RuntimeException finishFailure) {
            return resolveAmbiguousFinish(key, total, finishFailure);
        }
        return new StoredAttachment(key, total);
    }

    /**
     * A failed finish is ambiguous: GCS may have committed server-side. The object visible
     * at exactly the byte count written means exactly that — report the commit as the
     * success it was. Every other shape throws.
     */
    private StoredAttachment resolveAmbiguousFinish(String key, long total,
                                                    RuntimeException finishFailure)
            throws AttachmentStorageException {
        try {
            if (bucket.sizeOrAbsent(key) == total) {
                return new StoredAttachment(key, total);
            }
        } catch (RuntimeException probeFailure) {
            finishFailure.addSuppressed(probeFailure);
        }
        throw wrap("finish", key, finishFailure);
    }

    @Override
    public boolean exists(String caseNumber, String fileName) throws AttachmentStorageException {
        String key = key(caseNumber, fileName);
        try {
            return bucket.sizeOrAbsent(key) >= 0;
        } catch (RuntimeException e) {
            throw wrap("exists check", key, e);
        }
    }

    @Override
    public void verifyAccess() throws AttachmentStorageException {
        String sentinel = (prefix.isEmpty() ? "" : prefix + "/") + ".verify-" + UUID.randomUUID();
        byte[] probe = "attachment-receiver go-live probe".getBytes();
        String stage = "write";
        boolean written = false;
        try {
            bucket.create(sentinel, "application/octet-stream", probe);
            written = true;
            stage = "read";
            if (!Arrays.equals(probe, bucket.download(sentinel))) {
                throw new AttachmentStorageException(
                        "verify read returned different content than was written to bucket '"
                                + bucketName + "'");
            }
            stage = "delete";
            bucket.delete(sentinel);
        } catch (Exception e) {
            AttachmentStorageException failure = e instanceof AttachmentStorageException ase
                    ? ase
                    : new AttachmentStorageException(classify(stage, e), e);
            // A sentinel from a failed probe must not linger; best-effort, the probe's own
            // failure stays primary. Pointless only when delete itself just failed.
            if (written && !"delete".equals(stage)) {
                try {
                    bucket.delete(sentinel);
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /** Wrong-credential, wrong-target, and no-permission must read differently. */
    private String classify(String stage, Exception e) {
        if (e instanceof StorageException se) {
            int code = se.getCode();
            if (code == 401) {
                return "wrong credential: GCS rejected the identity (verify " + stage + ")";
            }
            if (code == 403) {
                return "no permission: " + stage + " denied on bucket '" + bucketName + "'";
            }
            if (code == 404) {
                return "wrong target: bucket '" + bucketName + "' does not exist (verify " + stage + ")";
            }
            return "verify " + stage + " failed on bucket '" + bucketName + "': "
                    + se.getMessage() + " (HTTP " + code + ")";
        }
        return "connectivity: cannot reach GCS (verify " + stage + "): " + e.getMessage();
    }

    private int fill(InputStream content, byte[] buffer, String key)
            throws AttachmentStorageException {
        try {
            // readNBytes loops over short reads; < buffer.length only ever means EOF.
            return content.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "stream failed mid-read for " + key + "; upload abandoned", e);
        }
    }

    private String key(String caseNumber, String fileName) {
        String base = caseNumber + "/" + fileName;
        return prefix.isEmpty() ? base : prefix + "/" + base;
    }

    private AttachmentStorageException wrap(String operation, String key, Exception e) {
        return new AttachmentStorageException(
                operation + " failed for gs://" + bucketName + "/" + key + ": " + e.getMessage(), e);
    }

    private static String normalize(String keyPrefix) {
        if (keyPrefix == null || keyPrefix.isBlank()) {
            return "";
        }
        String p = keyPrefix;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}

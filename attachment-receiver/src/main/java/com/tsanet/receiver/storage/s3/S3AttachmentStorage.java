package com.tsanet.receiver.storage.s3;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link AttachmentStorage} on AWS S3. The client is injected: region selection, and in
 * the hosted mode cross-account role assumption, are wiring concerns, not this class's.
 *
 * <p>Object keys are {@code [prefix/]caseNumber/fileName}. Per the SPI, {@code fileName}
 * is an opaque token: S3 keys have no traversal semantics, so a name containing {@code /}
 * or {@code ..} merely creates deeper key segments. {@code exists} builds keys through
 * the same method, so store and check can never disagree.
 *
 * <p>Streaming: bytes are read in 5 MiB buffers. A file that ends inside the first
 * buffer is written with a single atomic {@code PutObject}; anything larger becomes a
 * multipart upload, aborted on any failure. Incomplete multipart uploads are never
 * visible as objects, so the SPI's no-partial-visibility rule holds structurally. The
 * SDK only ever sees replayable byte bodies, never the caller's stream, so SDK-level
 * retries are safe and the stream is never closed by this class.
 *
 * <p>One deliberate policy for the ambiguous-complete edge (a
 * {@code CompleteMultipartUpload} that fails client-side after succeeding server-side):
 * when the failed complete's abort answers {@code NoSuchUpload} and the object is
 * visible, the store is treated as committed and reported as success.
 *
 * <p>Required IAM on the bucket/prefix: {@code s3:PutObject}, {@code s3:GetObject},
 * {@code s3:DeleteObject} (the verify sentinel), {@code s3:ListBucket} (without it a
 * missing key answers HEAD with 403, not 404, and {@code exists} throws on every absent
 * object), {@code s3:AbortMultipartUpload}. Note the HEAD ambiguity: a missing
 * <em>bucket</em> is also a bodiless 404, indistinguishable from a missing key —
 * {@code verifyAccess}, which probes with full error bodies, is the check that catches
 * that misconfiguration. Recommended bucket hygiene: an
 * {@code AbortIncompleteMultipartUpload} lifecycle rule, for the rare abort-also-failed
 * path (which leaves invisible parts accruing storage).
 */
public final class S3AttachmentStorage implements AttachmentStorage {

    /** S3's minimum non-last part size: 5 MiB exactly, not 5 MB. */
    static final int PART_SIZE = 5 * 1024 * 1024;

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    /**
     * @param keyPrefix optional key namespace (hosted mode: one prefix per tenant in a
     *                  shared bucket; tests: per-run isolation); slashes are normalized,
     *                  null or blank means no prefix
     */
    public S3AttachmentStorage(S3Client s3, String bucket, String keyPrefix) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        if (bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        this.prefix = normalize(keyPrefix);
    }

    @Override
    public StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException {
        String key = key(attachment.caseNumber(), attachment.fileName());
        byte[] first = new byte[PART_SIZE];
        int firstLength = fill(content, first, key);
        if (firstLength < PART_SIZE) {
            // EOF inside the first buffer: one atomic put, no partial-visibility window.
            try {
                s3.putObject(b -> {
                    b.bucket(bucket).key(key);
                    if (attachment.contentType() != null) {
                        b.contentType(attachment.contentType());
                    }
                }, RequestBody.fromBytes(Arrays.copyOf(first, firstLength)));
            } catch (SdkException e) {
                throw wrap("put", key, e);
            }
            return new StoredAttachment(key, firstLength);
        }
        return multipart(attachment, content, key, first);
    }

    private StoredAttachment multipart(IncomingAttachment attachment, InputStream content,
                                       String key, byte[] firstBuffer)
            throws AttachmentStorageException {
        String uploadId;
        try {
            uploadId = s3.createMultipartUpload(b -> {
                b.bucket(bucket).key(key);
                if (attachment.contentType() != null) {
                    b.contentType(attachment.contentType());
                }
            }).uploadId();
        } catch (SdkException e) {
            throw wrap("multipart start", key, e);
        }

        long total = 0;
        List<CompletedPart> completed = new ArrayList<>();
        try {
            byte[] buffer = firstBuffer;
            int length = firstBuffer.length;
            int partNumber = 0;
            boolean last = false;
            while (!last) {
                if (partNumber > 0) {
                    buffer = new byte[PART_SIZE];
                    length = fill(content, buffer, key);
                    if (length == 0) {
                        break; // EOF landed exactly on the previous part's boundary.
                    }
                }
                partNumber++;
                final int pn = partNumber;
                byte[] body = length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
                String eTag = s3.uploadPart(
                        b -> b.bucket(bucket).key(key).uploadId(uploadId).partNumber(pn),
                        RequestBody.fromBytes(body)).eTag();
                completed.add(CompletedPart.builder().partNumber(pn).eTag(eTag).build());
                total += length;
                last = length < PART_SIZE;
            }
        } catch (AttachmentStorageException uploadFailure) {
            // Already carries the key and phase; re-wrapping would just nest the message.
            abortQuietly(key, uploadId, uploadFailure);
            throw uploadFailure;
        } catch (Exception uploadFailure) {
            abortQuietly(key, uploadId, uploadFailure);
            throw wrap("store", key, uploadFailure);
        }

        try {
            List<CompletedPart> parts = completed;
            s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(mu -> mu.parts(parts)));
        } catch (Exception completeFailure) {
            return resolveAmbiguousComplete(key, uploadId, total, completeFailure);
        }
        return new StoredAttachment(key, total);
    }

    /** Abort after an upload-phase failure; the failure being thrown stays primary. */
    private void abortQuietly(String key, String uploadId, Exception primary) {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
        } catch (Exception abortFailure) {
            primary.addSuppressed(abortFailure);
        }
    }

    /**
     * A failed complete is ambiguous: S3 may have committed server-side. Abort answering
     * {@code NoSuchUpload} while the object is visible means exactly that — report the
     * commit as the success it was. Every other shape aborts and throws.
     */
    private StoredAttachment resolveAmbiguousComplete(String key, String uploadId, long total,
                                                      Exception completeFailure)
            throws AttachmentStorageException {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
        } catch (NoSuchUploadException uploadGone) {
            try {
                if (existsKey(key)) {
                    return new StoredAttachment(key, total);
                }
            } catch (Exception headFailure) {
                completeFailure.addSuppressed(headFailure);
            }
            completeFailure.addSuppressed(uploadGone);
        } catch (Exception abortFailure) {
            completeFailure.addSuppressed(abortFailure);
        }
        throw wrap("complete", key, completeFailure);
    }

    @Override
    public boolean exists(String caseNumber, String fileName) throws AttachmentStorageException {
        return existsKey(key(caseNumber, fileName));
    }

    private boolean existsKey(String key) throws AttachmentStorageException {
        try {
            s3.headObject(b -> b.bucket(bucket).key(key));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                // Includes NoSuchKeyException; HEAD has no error body, so status is the
                // only reliable discriminator across SDK versions.
                return false;
            }
            throw wrap("exists check", key, e);
        } catch (SdkException e) {
            throw new AttachmentStorageException(
                    "exists check failed for " + key + ": cannot reach S3: " + e.getMessage(), e);
        }
    }

    @Override
    public void verifyAccess() throws AttachmentStorageException {
        String sentinel = (prefix.isEmpty() ? "" : prefix + "/")
                + ".verify-" + UUID.randomUUID();
        byte[] probe = "attachment-receiver go-live probe".getBytes();
        String stage = "write";
        boolean written = false;
        try {
            s3.putObject(b -> b.bucket(bucket).key(sentinel), RequestBody.fromBytes(probe));
            written = true;
            stage = "read";
            try (InputStream in = s3.getObject(b -> b.bucket(bucket).key(sentinel))) {
                if (in.readAllBytes().length != probe.length) {
                    throw new AttachmentStorageException(
                            "verify read returned wrong length from bucket '" + bucket + "'");
                }
            }
            stage = "delete";
            s3.deleteObject(b -> b.bucket(bucket).key(sentinel));
        } catch (Exception e) {
            AttachmentStorageException failure = e instanceof AttachmentStorageException ase
                    ? ase
                    : new AttachmentStorageException(classify(stage, e), e);
            // A sentinel from a failed probe must not linger; best-effort, the probe's
            // own failure stays primary. Pointless only when delete itself just failed.
            if (written && !"delete".equals(stage)) {
                try {
                    s3.deleteObject(b -> b.bucket(bucket).key(sentinel));
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /** Wrong-credential, wrong-target, and no-permission must read differently. */
    private String classify(String stage, Exception e) {
        if (e instanceof S3Exception s3e) {
            String code = s3e.awsErrorDetails() != null ? s3e.awsErrorDetails().errorCode() : "";
            if ("NoSuchBucket".equals(code) || s3e.statusCode() == 404) {
                return "wrong target: bucket '" + bucket + "' does not exist (verify " + stage + ")";
            }
            if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)
                    || "ExpiredToken".equals(code)) {
                return "wrong credential: S3 rejected the identity (" + code + ", verify " + stage + ")";
            }
            if ("AccessDenied".equals(code)) {
                return "no permission: " + stage + " denied on bucket '" + bucket + "'";
            }
            return "verify " + stage + " failed on bucket '" + bucket + "': "
                    + code + " (HTTP " + s3e.statusCode() + ")";
        }
        if (e instanceof SdkClientException) {
            return "connectivity: cannot reach S3 (verify " + stage + "): " + e.getMessage();
        }
        return "verify " + stage + " failed on bucket '" + bucket + "': " + e.getMessage();
    }

    private int fill(InputStream content, byte[] buffer, String key)
            throws AttachmentStorageException {
        try {
            // readNBytes loops over short reads; < buffer.length only ever means EOF.
            return content.readNBytes(buffer, 0, buffer.length);
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "stream failed mid-read for " + key + "; upload aborted", e);
        }
    }

    private String key(String caseNumber, String fileName) {
        String base = caseNumber + "/" + fileName;
        return prefix.isEmpty() ? base : prefix + "/" + base;
    }

    private AttachmentStorageException wrap(String operation, String key, Exception e) {
        return new AttachmentStorageException(
                operation + " failed for s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
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

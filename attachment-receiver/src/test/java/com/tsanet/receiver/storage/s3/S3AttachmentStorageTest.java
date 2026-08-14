package com.tsanet.receiver.storage.s3;

import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static com.tsanet.receiver.storage.s3.S3AttachmentStorage.PART_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline behavior of the S3 adapter against the recording stub. Live-bucket coverage,
 * including the shared contract test, is {@code S3AttachmentStorageLiveTest}.
 */
class S3AttachmentStorageTest {

    private StubS3Client s3;
    private S3AttachmentStorage storage;

    @BeforeEach
    void setUp() {
        s3 = new StubS3Client();
        storage = new S3AttachmentStorage(s3, "test-bucket", null);
    }

    private static IncomingAttachment attachment(String fileName) {
        return new IncomingAttachment("01234567", fileName, null, -1);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void smallFileUsesOneAtomicPut() throws Exception {
        StoredAttachment stored = storage.store(attachment("small.log"),
                new ByteArrayInputStream(bytes(10)));
        assertEquals("01234567/small.log", stored.storageKey());
        assertEquals(10, stored.bytesWritten());
        assertTrue(s3.calls.contains("put:01234567/small.log"));
        assertFalse(s3.calls.stream().anyMatch(c -> c.startsWith("createMultipart")),
                "a file inside one buffer must not start a multipart upload");
        assertArrayEquals(bytes(10), s3.objects.get("01234567/small.log"));
    }

    @Test
    void emptyStreamStoresAZeroByteObject() throws Exception {
        StoredAttachment stored = storage.store(attachment("empty.txt"),
                new ByteArrayInputStream(new byte[0]));
        assertEquals(0, stored.bytesWritten());
        assertEquals(0, s3.objects.get("01234567/empty.txt").length);
    }

    @Test
    void exactlyOneBufferGoesMultipartWithASinglePart() throws Exception {
        // EOF lands exactly on the buffer boundary: the adapter cannot know it's EOF
        // yet, so this is a one-part multipart upload (a single part may be any size).
        StoredAttachment stored = storage.store(attachment("exact.bin"),
                new ByteArrayInputStream(bytes(PART_SIZE)));
        assertEquals(PART_SIZE, stored.bytesWritten());
        assertTrue(s3.calls.contains("uploadPart:1"));
        assertFalse(s3.calls.contains("uploadPart:2"), "EOF on the boundary must not add an empty part");
        assertTrue(s3.calls.stream().anyMatch(c -> c.startsWith("complete:")));
        assertEquals(PART_SIZE, s3.objects.get("01234567/exact.bin").length);
    }

    @Test
    void largeFileMultipartsAndCountsEveryByte() throws Exception {
        int size = PART_SIZE + 100;
        StoredAttachment stored = storage.store(attachment("big.bin"),
                new ByteArrayInputStream(bytes(size)));
        assertEquals(size, stored.bytesWritten());
        assertTrue(s3.calls.contains("uploadPart:1"));
        assertTrue(s3.calls.contains("uploadPart:2"));
        assertEquals(size, s3.objects.get("01234567/big.bin").length);
    }

    @Test
    void dribblingStreamStillFillsBuffers() throws Exception {
        // One byte per read(): readNBytes must loop over short reads, not treat them as EOF.
        InputStream dribble = new InputStream() {
            private int remaining = 10;

            @Override
            public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (remaining <= 0) {
                    return -1;
                }
                b[off] = 'x';
                remaining--;
                return 1;
            }
        };
        assertEquals(10, storage.store(attachment("dribble.txt"), dribble).bytesWritten());
    }

    @Test
    void streamFailureMidMultipartAbortsTheUpload() {
        InputStream failsAfterOnePart = new SequenceThenFail(bytes(PART_SIZE));
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("dies.bin"), failsAfterOnePart));
        assertTrue(s3.calls.stream().anyMatch(c -> c.startsWith("abort:")),
                "a failed multipart upload must be aborted");
        assertTrue(s3.pendingUploads.isEmpty(), "no multipart upload may remain pending");
        assertFalse(s3.objects.containsKey("01234567/dies.bin"), "no partial object may be visible");
    }

    @Test
    void abortFailureIsSuppressedNotPrimary() {
        s3.failUploadPartWith = () -> SdkClientException.create("network died (simulated)");
        s3.failAbortWith = () -> SdkClientException.create("abort also died (simulated)");
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("worse.bin"),
                        new ByteArrayInputStream(bytes(PART_SIZE + 1))));
        assertTrue(e.getMessage().contains("network died"),
                "the original failure stays primary: " + e.getMessage());
        assertEquals(1, e.getCause().getSuppressed().length,
                "the abort failure must ride along as suppressed");
    }

    @Test
    void ambiguousCompleteThatActuallyCommittedIsReportedAsSuccess() throws Exception {
        // complete() fails client-side but committed server-side; abort finds the upload
        // gone; the object is visible -> the store is the success it actually was.
        s3.failCompleteWith = () -> SdkClientException.create("response timed out (simulated)");
        s3.completeCommitsDespiteFailure = true;
        s3.failAbortWith = () -> NoSuchUploadException.builder().statusCode(404).build();
        int size = PART_SIZE + 5;
        StoredAttachment stored = storage.store(attachment("ambiguous.bin"),
                new ByteArrayInputStream(bytes(size)));
        assertEquals(size, stored.bytesWritten());
        assertTrue(s3.objects.containsKey("01234567/ambiguous.bin"));
    }

    @Test
    void ambiguousCompleteWithNoVisibleObjectStillThrows() {
        s3.failCompleteWith = () -> SdkClientException.create("response timed out (simulated)");
        s3.completeCommitsDespiteFailure = false;
        s3.failAbortWith = () -> NoSuchUploadException.builder().statusCode(404).build();
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("gone.bin"),
                        new ByteArrayInputStream(bytes(PART_SIZE + 5))));
    }

    @Test
    void exists404IsFalseButOtherErrorsThrow() throws Exception {
        assertFalse(storage.exists("01234567", "never.txt"));

        s3.failHeadWith = () -> S3Exception.builder().statusCode(403).message("AccessDenied").build();
        assertThrows(AttachmentStorageException.class,
                () -> storage.exists("01234567", "never.txt"),
                "a 403 (no ListBucket permission) must throw, not read as absent");

        s3.failHeadWith = () -> SdkClientException.create("connection refused");
        assertThrows(AttachmentStorageException.class,
                () -> storage.exists("01234567", "never.txt"),
                "an outage must throw, not read as absent");
    }

    @Test
    void keyPrefixNamespacesEveryKey() throws Exception {
        S3AttachmentStorage prefixed = new S3AttachmentStorage(s3, "test-bucket", "/tenants/acme/");
        StoredAttachment stored = prefixed.store(attachment("diag.log"),
                new ByteArrayInputStream(bytes(3)));
        assertEquals("tenants/acme/01234567/diag.log", stored.storageKey());
        assertTrue(prefixed.exists("01234567", "diag.log"),
                "exists must build the same prefixed key that store used");
    }

    @Test
    void verifyAccessDistinguishesTheThreeFailureShapes() {
        s3.failPutWith = () -> S3Exception.builder().statusCode(403)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("AccessDenied").build())
                .build();
        AttachmentStorageException denied = assertThrows(AttachmentStorageException.class,
                () -> storage.verifyAccess());
        assertTrue(denied.getMessage().contains("no permission"), denied.getMessage());

        s3.failPutWith = () -> S3Exception.builder().statusCode(404)
                .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("NoSuchBucket").build())
                .build();
        AttachmentStorageException wrongTarget = assertThrows(AttachmentStorageException.class,
                () -> storage.verifyAccess());
        assertTrue(wrongTarget.getMessage().contains("test-bucket"), wrongTarget.getMessage());

        s3.failPutWith = () -> SdkClientException.create("UnknownHostException: s3.example");
        AttachmentStorageException unreachable = assertThrows(AttachmentStorageException.class,
                () -> storage.verifyAccess());
        assertTrue(unreachable.getMessage().contains("connectivity"), unreachable.getMessage());
    }

    @Test
    void verifyAccessCleansUpItsSentinel() throws Exception {
        storage.verifyAccess();
        assertTrue(s3.objects.isEmpty(), "the sentinel object must be deleted");
    }

    @Test
    void verifyAccessCleansUpItsSentinelEvenWhenTheProbeFails() {
        // The put succeeded, the read stage fails: the sentinel must not linger.
        s3.failGetWith = () -> SdkClientException.create("read stage died (simulated)");
        assertThrows(AttachmentStorageException.class, () -> storage.verifyAccess());
        assertTrue(s3.objects.isEmpty(),
                "a failed probe must still best-effort delete its sentinel");
    }

    /** Serves the given bytes, then fails: the mid-stream death of a real upload. */
    private static final class SequenceThenFail extends InputStream {
        private final byte[] source;
        private int position;

        SequenceThenFail(byte[] source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            if (position < source.length) {
                return source[position++] & 0xFF;
            }
            throw new IOException("stream died after " + position + " bytes (simulated)");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (position >= source.length) {
                throw new IOException("stream died after " + position + " bytes (simulated)");
            }
            int n = Math.min(len, source.length - position);
            System.arraycopy(source, position, b, off, n);
            position += n;
            return n;
        }
    }
}

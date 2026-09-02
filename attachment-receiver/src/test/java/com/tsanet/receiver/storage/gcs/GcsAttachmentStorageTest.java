package com.tsanet.receiver.storage.gcs;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static com.tsanet.receiver.storage.gcs.GcsAttachmentStorage.BUFFER_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared contract against the strict in-memory double (S3 and Azure lean on their
 * live tests for the contract run; GCS has no credentials configured locally, so proving
 * contract compliance offline is what this file adds), plus the GCS-specific edges the
 * contract cannot reach: the single-shot/resumable split, abandon-not-finish on failure,
 * and the ambiguous-finish policy.
 */
class GcsAttachmentStorageTest extends AttachmentStorageContractTest {

    @Override
    protected AttachmentStorage newStorage() {
        return new GcsAttachmentStorage(new InMemoryGcsBucket(), "tenant-bucket", "tenants/acme");
    }

    private static IncomingAttachment attachment(String fileName) {
        return new IncomingAttachment("01234567", fileName, "application/octet-stream", -1);
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void smallFileUsesSingleShotCreate() throws Exception {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        byte[] content = bytes(1024);
        storage.store(attachment("small.bin"), new java.io.ByteArrayInputStream(content));
        assertEquals(0, gcs.abandonCount, "a completed store never abandons");
        assertTrue(storage.exists("01234567", "small.bin"));
    }

    @Test
    void fileLargerThanOneBufferGoesResumableAndCountsEveryByte() throws Exception {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        byte[] content = bytes(BUFFER_SIZE + 4096);
        var stored = storage.store(attachment("big.bin"), new java.io.ByteArrayInputStream(content));
        assertEquals(content.length, stored.bytesWritten());
        assertEquals(0, gcs.abandonCount);
    }

    @Test
    void eofExactlyOnBufferBoundaryStillStoresEveryByte() throws Exception {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        byte[] content = bytes(BUFFER_SIZE);
        var stored = storage.store(attachment("exact.bin"), new java.io.ByteArrayInputStream(content));
        assertEquals(BUFFER_SIZE, stored.bytesWritten());
        assertTrue(storage.exists("01234567", "exact.bin"));
    }

    @Test
    void streamFailureMidResumableAbandonsAndLeavesNothingVisible() {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        // Yield more than one buffer, then die: forces the resumable path, then fails it.
        InMemoryGcsBucketStreams.DyingStream dying =
                new InMemoryGcsBucketStreams.DyingStream(BUFFER_SIZE + 1024);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("truncated.bin"), dying));
        assertEquals(1, gcs.abandonCount, "a mid-stream failure must abandon, never finish");
        assertNull(gcs.contentOf("01234567/truncated.bin"), "an abandoned upload leaves no visible object");
    }

    @Test
    void ambiguousFinishThatActuallyCommittedIsReportedAsSuccess() throws Exception {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        gcs.failFinish = new StorageException(503, "finish RPC failed after commit");
        gcs.finishCommitsBeforeFailing = true;
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        byte[] content = bytes(BUFFER_SIZE + 8);
        var stored = storage.store(attachment("ambiguous.bin"), new java.io.ByteArrayInputStream(content));
        assertEquals(content.length, stored.bytesWritten(),
                "a finish that committed server-side is the success it was");
    }

    @Test
    void ambiguousFinishWithNothingCommittedThrows() {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        gcs.failFinish = new StorageException(503, "finish RPC failed, nothing committed");
        gcs.finishCommitsBeforeFailing = false;
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("lost.bin"),
                        new java.io.ByteArrayInputStream(bytes(BUFFER_SIZE + 8))));
    }

    @Test
    void sameNameStoreOverwrites() throws Exception {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        storage.store(attachment("dup.bin"), new java.io.ByteArrayInputStream(bytes(100)));
        storage.store(attachment("dup.bin"), new java.io.ByteArrayInputStream(bytes(200)));
        assertEquals(200, gcs.contentOf("01234567/dup.bin").length);
    }

    @Test
    void verifyAccessClassifiesNoPermission() {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        gcs.failCreate = new StorageException(403, "forbidden");
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "locked-bucket", null);
        AttachmentStorageException e =
                assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("no permission"), e.getMessage());
        assertTrue(e.getMessage().contains("locked-bucket"), e.getMessage());
    }

    @Test
    void verifyAccessClassifiesWrongTarget() {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        gcs.failCreate = new StorageException(404, "no such bucket");
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "ghost-bucket", null);
        AttachmentStorageException e =
                assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("wrong target"), e.getMessage());
    }

    @Test
    void existsThrowsWhenTheBackendCannotAnswer() {
        InMemoryGcsBucket gcs = new InMemoryGcsBucket();
        gcs.failSizeOrAbsent = new StorageException(500, "backend down");
        GcsAttachmentStorage storage = new GcsAttachmentStorage(gcs, "b", null);
        // The SPI forbids answering false on an indeterminate check.
        assertThrows(AttachmentStorageException.class, () -> storage.exists("01234567", "x.bin"));
    }

    /** Streams for the failure edges; kept out of the fake so the fake stays semantics-only. */
    private static final class InMemoryGcsBucketStreams {
        static final class DyingStream extends InputStream {
            private final long deathAt;
            private long served;

            DyingStream(long deathAt) {
                this.deathAt = deathAt;
            }

            @Override
            public int read() throws IOException {
                if (served < deathAt) {
                    served++;
                    return 'x';
                }
                throw new IOException("stream died mid-read (simulated)");
            }
        }
    }
}

package com.tsanet.receiver.storage.s3;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static com.tsanet.receiver.storage.s3.S3AttachmentStorage.PART_SIZE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issue-mandated real-bucket acceptance run: the shared contract test bound to a
 * live bucket, plus the abort-after-partial-commit criterion the in-memory reference
 * cannot exercise (added from PR #54's gate review).
 *
 * <p>Gated on {@code S3_CONTRACT_TEST_BUCKET}; region and credentials come from the
 * default provider chain. Each instance works under a random key prefix, and tears its
 * keys down afterward, so a shared disposable bucket is safe.
 */
@EnabledIfEnvironmentVariable(named = "S3_CONTRACT_TEST_BUCKET", matches = ".+")
class S3AttachmentStorageLiveTest extends AttachmentStorageContractTest {

    private final String bucket = System.getenv("S3_CONTRACT_TEST_BUCKET");
    private final String runPrefix = "contract-" + UUID.randomUUID();
    private final S3Client s3 = S3Client.create();

    @Override
    protected AttachmentStorage newStorage() {
        return new S3AttachmentStorage(s3, bucket, runPrefix);
    }

    @AfterEach
    void tearDownKeys() {
        s3.listObjectsV2(b -> b.bucket(bucket).prefix(runPrefix)).contents()
                .forEach(o -> s3.deleteObject(b -> b.bucket(bucket).key(o.key())));
        // If the abort test ever fails, the lingering upload it detected must not be
        // left accruing storage invisibly; object teardown alone cannot see it.
        s3.listMultipartUploads(b -> b.bucket(bucket).prefix(runPrefix)).uploads()
                .forEach(u -> s3.abortMultipartUpload(
                        b -> b.bucket(bucket).key(u.key()).uploadId(u.uploadId())));
    }

    @Test
    void abortAfterPartialCommitLeavesNothingVisible() throws Exception {
        AttachmentStorage storage = newStorage();
        IncomingAttachment attachment =
                new IncomingAttachment("01234567", "partial.bin", null, -1);
        // Fail after one full part has really been uploaded to S3.
        InputStream diesAfterOnePart = new InputStream() {
            private long served;

            @Override
            public int read() throws IOException {
                if (served < PART_SIZE + 1024) {
                    served++;
                    return 'x';
                }
                throw new IOException("stream died after a committed part (simulated)");
            }
        };
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment, diesAfterOnePart));
        assertFalse(storage.exists("01234567", "partial.bin"),
                "an aborted multipart upload must leave no visible object");
        assertTrue(s3.listMultipartUploads(b -> b.bucket(bucket).prefix(runPrefix))
                        .uploads().isEmpty(),
                "no multipart upload may linger under this run's prefix");
    }
}

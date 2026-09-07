package com.tsanet.receiver.storage.gcs;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static com.tsanet.receiver.storage.gcs.GcsAttachmentStorage.BUFFER_SIZE;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The real-bucket acceptance run: the shared contract test bound to a live GCS bucket.
 *
 * <p>Gated on {@code GCS_CONTRACT_TEST_BUCKET}; project and credentials come from
 * Application Default Credentials. Each instance works under a random object-name prefix and
 * tears its objects down afterward, so a shared disposable bucket is safe. There is no
 * multipart-upload teardown twin of the S3 live test here because GCS has no abort surface:
 * an abandoned resumable session leaves no object and expires on its own.
 */
@EnabledIfEnvironmentVariable(named = "GCS_CONTRACT_TEST_BUCKET", matches = ".+")
class GcsAttachmentStorageLiveTest extends AttachmentStorageContractTest {

    private final String bucket = System.getenv("GCS_CONTRACT_TEST_BUCKET");
    private final String runPrefix = "contract-" + UUID.randomUUID();
    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    @Override
    protected AttachmentStorage newStorage() {
        return GcsAttachmentStorage.forBucket(storage, bucket, runPrefix);
    }

    @Test
    void completedResumableObjectCarriesThisAttemptsMarkerAndSingleRequestObjectsDoNot() throws Exception {
        // The fact tsanetgit/Connect_SDK#69's identity resolution rests on, for GCS: custom
        // metadata on the BlobInfo that opens the resumable session lands on the finalized
        // object. Env-gated like the rest of the class; run it when credentials exist.
        AttachmentStorage adapter = newStorage();
        var resumable = adapter.store(new IncomingAttachment("01234567", "marked.bin", null, -1),
                new ByteArrayInputStream(new byte[BUFFER_SIZE + 1024]));
        Blob marked = storage.get(BlobId.of(bucket, resumable.storageKey()));
        assertNotNull(marked, "the finalized object must be visible");
        assertNotNull(marked.getMetadata(), "metadata from the session's BlobInfo must be on the finalized object");
        UUID.fromString(marked.getMetadata().get(GcsAttachmentStorage.ATTEMPT_METADATA_KEY));
        var single = adapter.store(new IncomingAttachment("01234567", "small.bin", null, -1),
                new ByteArrayInputStream(new byte[16]));
        Blob unmarked = storage.get(BlobId.of(bucket, single.storageKey()));
        assertNull(unmarked.getMetadata() == null ? null
                        : unmarked.getMetadata().get(GcsAttachmentStorage.ATTEMPT_METADATA_KEY),
                "single-request objects stay unmarked");
    }

    @AfterEach
    void tearDownObjects() {
        for (Blob blob : storage.list(bucket, Storage.BlobListOption.prefix(runPrefix)).iterateAll()) {
            storage.delete(blob.getBlobId());
        }
    }
}

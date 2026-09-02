package com.tsanet.receiver.storage.gcs;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.UUID;

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

    @AfterEach
    void tearDownObjects() {
        for (Blob blob : storage.list(bucket, Storage.BlobListOption.prefix(runPrefix)).iterateAll()) {
            storage.delete(blob.getBlobId());
        }
    }
}

package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.azure.AzureFilesAttachmentStorage;
import com.tsanet.receiver.storage.gcs.GcsAttachmentStorage;
import com.tsanet.receiver.storage.s3.S3AttachmentStorage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The factory's job is dispatch and config validation; construction is lazy, so these tests
 * never touch a backend. They cover the three known backends' happy paths (each returns its
 * adapter type), the required-property errors, and the unknown-backend error.
 *
 * <p>The GCS happy path is deliberately not asserted here: building a real {@code Storage}
 * with Application Default Credentials needs an environment this offline test does not have,
 * and forcing fake credentials would prove nothing about the factory. GCS construction is
 * proven by {@code GcsAttachmentStorageLiveTest} and by {@code verifyAccess} at onboarding;
 * this file asserts the GCS dispatch and its required-property validation instead.
 */
class RegisteringStorageFactoryTest {

    private final RegisteringStorageFactory factory = new RegisteringStorageFactory();

    private static TenantConfig config(String backend, Map<String, String> storageProperties) {
        return new TenantConfig("acme", "push-password", backend, storageProperties, Map.of());
    }

    @Test
    void unknownBackendThrowsNamingTheId() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("wasabi", Map.of("bucket", "b"))));
        assertTrue(e.getMessage().contains("wasabi"), e.getMessage());
    }

    @Test
    void s3WithRegionBucketAndStaticKeysBuildsS3Adapter() throws Exception {
        AttachmentStorage storage = factory.create(config("s3", Map.of(
                "region", "us-east-1",
                "bucket", "member-bucket",
                "accessKeyId", "AKIAEXAMPLE",
                "secretAccessKey", "secret",
                "prefix", "tenants/acme")));
        assertInstanceOf(S3AttachmentStorage.class, storage);
    }

    @Test
    void s3WithoutRegionIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("s3", Map.of("bucket", "b"))));
        assertTrue(e.getMessage().contains("region"), e.getMessage());
    }

    @Test
    void s3WithOnlyOneKeyOfThePairIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("s3", Map.of(
                        "region", "us-east-1", "bucket", "b", "accessKeyId", "AKIAEXAMPLE"))));
        assertTrue(e.getMessage().contains("together"), e.getMessage());
    }

    @Test
    void azureWithConnectionStringAndShareBuildsAzureAdapter() throws Exception {
        // A well-formed but fake connection string; the client builds without a network call.
        String connectionString = "DefaultEndpointsProtocol=https;AccountName=acct;"
                + "AccountKey=Zm9vYmFyYmF6;EndpointSuffix=core.windows.net";
        AttachmentStorage storage = factory.create(config("azure", Map.of(
                "connectionString", connectionString,
                "shareName", "attachments",
                "directoryPrefix", "tenants/acme")));
        assertInstanceOf(AzureFilesAttachmentStorage.class, storage);
    }

    @Test
    void azureWithoutTargetIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure", Map.of("directoryPrefix", "x"))));
        assertTrue(e.getMessage().contains("connectionString"), e.getMessage());
    }

    @Test
    void gcsWithoutBucketIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("gcs", Map.of("projectId", "p"))));
        assertTrue(e.getMessage().contains("bucket"), e.getMessage());
    }

    @Test
    void gcsCredentialsJsonThatIsNotAKeyIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("gcs", Map.of(
                        "bucket", "member-bucket",
                        "credentialsJson", "{\"not\":\"a service account key\"}"))));
        assertTrue(e.getMessage().contains("credentialsJson"), e.getMessage());
    }
}

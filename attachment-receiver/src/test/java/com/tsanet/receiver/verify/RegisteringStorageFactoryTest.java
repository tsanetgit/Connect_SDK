package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.azure.AzureBlobAttachmentStorage;
import com.tsanet.receiver.storage.azure.AzureFilesAttachmentStorage;
import com.tsanet.receiver.storage.gcs.GcsAttachmentStorage;
import com.tsanet.receiver.storage.s3.S3AttachmentStorage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // The wrong-but-well-formed inputs for the azure branch: each is a value an operator
    // could plausibly paste, and each carries a marker standing in for a secret. The
    // property under test is that the marker reaches no exception anywhere in the chain,
    // and that the escaping type is the factory's contract exception rather than the SDK's.

    @Test
    void azureMalformedSasUrlNeverEchoesItsValue() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure", Map.of("sasUrl", "not a url at all sig=MARKER"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
        assertTrue(e.getMessage().contains("sasUrl"), e.getMessage());
    }

    @Test
    void azureConnectionStringPastedIntoSasUrlNeverEchoesTheAccountKey() {
        // The realistic mistake: the whole connection string, account key included, dropped
        // into the sasUrl key. Before the guard this surfaced as
        // "MalformedURLException: no protocol: DefaultEndpointsProtocol=...;AccountKey=...".
        String pasted = "DefaultEndpointsProtocol=https;AccountName=acct;"
                + "AccountKey=MARKER;EndpointSuffix=core.windows.net";
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure", Map.of("sasUrl", pasted))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
    }

    @Test
    void azureSasUrlWithoutShareNameIsAContractErrorNotAnNpe() {
        // A well-formed URL with no share path made the SDK throw a bare NullPointerException,
        // which is not the StorageFactory contract's declared exception.
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure", Map.of(
                        "sasUrl", "https://acct.file.core.windows.net/?sv=2024-01-01&sig=MARKER"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
    }

    @Test
    void azureMalformedConnectionStringNeverEchoesItsValue() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure", Map.of(
                        "connectionString", "this is not a connection string AccountKey=MARKER",
                        "shareName", "attachments"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
        assertTrue(e.getMessage().contains("connectionString"), e.getMessage());
    }

    /** Every message in the cause chain, so a leak two causes deep is still caught. */
    private static String fullChain(Throwable t) {
        StringBuilder chain = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            chain.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
        }
        return chain.toString();
    }

    @Test
    void unknownBackendMessageListsEveryRegisteredBackend() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("wasabi", Map.of())));
        for (String backend : new String[] {"s3", "azure", "azure_blob", "gcs"}) {
            assertTrue(e.getMessage().contains(backend), backend + " missing from: " + e.getMessage());
        }
    }

    @Test
    void azureBlobWithConnectionStringAndContainerBuildsBlobAdapter() throws Exception {
        String connectionString = "DefaultEndpointsProtocol=https;AccountName=acct;"
                + "AccountKey=Zm9vYmFyYmF6;EndpointSuffix=core.windows.net";
        AttachmentStorage storage = factory.create(config("azure_blob", Map.of(
                "connectionString", connectionString,
                "containerName", "attachments",
                "prefix", "tenants/acme")));
        assertInstanceOf(AzureBlobAttachmentStorage.class, storage);
    }

    @Test
    void azureBlobWithHttpsContainerSasUrlBuildsBlobAdapter() throws Exception {
        // A well-formed container SAS URL with a fake signature; the client builds offline.
        AttachmentStorage storage = factory.create(config("azure_blob", Map.of(
                "sasUrl", "https://acct.blob.core.windows.net/attachments?sv=2024-01-01&sp=rw&sig=Zm9v",
                "prefix", "tenants/acme")));
        assertInstanceOf(AzureBlobAttachmentStorage.class, storage);
    }

    @Test
    void azureBlobHttpSasUrlIsRejectedWithoutEchoingTheToken() {
        // The builder accepts http:// and would send the SAS token in cleartext; the
        // factory's message promises https, so the factory must be what enforces it.
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of(
                        "sasUrl", "http://acct.blob.core.windows.net/attachments?sv=2024-01-01&sig=MARKER"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
        assertTrue(e.getMessage().contains("https"), e.getMessage());
    }

    @Test
    void azureBlobWithoutTargetIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of("prefix", "x"))));
        assertTrue(e.getMessage().contains("connectionString"), e.getMessage());
    }

    @Test
    void azureBlobWithConnectionStringButNoContainerIsAConfigError() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of(
                        "connectionString", "DefaultEndpointsProtocol=https;AccountName=acct;"
                                + "AccountKey=Zm9vYmFyYmF6;EndpointSuffix=core.windows.net"))));
        assertTrue(e.getMessage().contains("containerName"), e.getMessage());
    }

    // The azure_blob branch gets the same wrong-but-well-formed probes as the azure branch:
    // every value carries a MARKER standing in for a secret, and the property is that the
    // marker reaches no exception anywhere in the chain.

    @Test
    void azureBlobMalformedSasUrlNeverEchoesItsValue() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of("sasUrl", "not a url at all sig=MARKER"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
        assertTrue(e.getMessage().contains("sasUrl"), e.getMessage());
    }

    @Test
    void azureBlobConnectionStringPastedIntoSasUrlNeverEchoesTheAccountKey() {
        String pasted = "DefaultEndpointsProtocol=https;AccountName=acct;"
                + "AccountKey=MARKER;EndpointSuffix=core.windows.net";
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of("sasUrl", pasted))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
    }

    @Test
    void azureBlobSasUrlWithoutContainerNameIsAConfigError() {
        // An account-level SAS URL: well-formed, but there is no container to write into.
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of(
                        "sasUrl", "https://acct.blob.core.windows.net/?sv=2024-01-01&sig=MARKER"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
        assertTrue(e.getMessage().contains("container name"), e.getMessage());
    }

    @Test
    void azureBlobMalformedConnectionStringNeverEchoesItsValue() {
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> factory.create(config("azure_blob", Map.of(
                        "connectionString", "this is not a connection string AccountKey=MARKER",
                        "containerName", "attachments"))));
        assertFalse(fullChain(e).contains("MARKER"), fullChain(e));
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

package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.sas.BlobContainerSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.tsanet.receiver.storage.azure.AzureBlobAttachmentStorage.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real-container acceptance run: the shared contract test bound to a live Azure Blob
 * container, plus the facts the offline double can only assume:
 * <ul>
 *   <li>a blob that received staged blocks and was never committed is invisible to
 *       {@code exists} (it is enumerable only as an uncommitted blob);</li>
 *   <li>the go-live probe passes on an {@code rw} SAS and leaves no committed blob; a
 *       {@code cw}-only SAS fails at the read stage and a read-only SAS at the write stage,
 *       both classified as no permission; a tampered signature is classified as wrong
 *       credential (the error-code-before-status ordering, live);</li>
 *   <li>hostile file names round-trip under their literal encoded name: the first run of
 *       this test, before encoding, showed the service normalizing {@code ..}, a trailing
 *       dot, and a backslash, so "the listed name equals the encoded name" is the assertion
 *       that proves no normalization can move a blob out of its prefix.</li>
 * </ul>
 *
 * <p>Gated on {@code AZURE_BLOB_CONNECTION_STRING} + {@code AZURE_BLOB_CONTRACT_TEST_CONTAINER}.
 * Each instance works under a random name prefix and deletes its committed blobs
 * afterward; uncommitted probe blobs expire on their own, so a shared disposable container
 * is safe.
 */
@EnabledIfEnvironmentVariable(named = "AZURE_BLOB_CONNECTION_STRING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AZURE_BLOB_CONTRACT_TEST_CONTAINER", matches = ".+")
class AzureBlobAttachmentStorageLiveTest extends AttachmentStorageContractTest {

    private final String runPrefix = "contract-" + UUID.randomUUID();
    private final BlobContainerClient container = new BlobContainerClientBuilder()
            .connectionString(System.getenv("AZURE_BLOB_CONNECTION_STRING"))
            .containerName(System.getenv("AZURE_BLOB_CONTRACT_TEST_CONTAINER"))
            .buildClient();

    @Override
    protected AttachmentStorage newStorage() {
        return AzureBlobAttachmentStorage.forContainer(container, runPrefix);
    }

    @AfterEach
    void tearDownPrefix() {
        for (String name : names(runPrefix, false)) {
            container.getBlobClient(name).deleteIfExists();
        }
    }

    @Test
    void abortAfterStagedBlocksLeavesNothingVisible() throws Exception {
        AttachmentStorage storage = newStorage();
        IncomingAttachment attachment = new IncomingAttachment("01234567", "partial.bin", null, -1);
        // Fail after one full block has really been staged with the service.
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment, new AzureBlobAttachmentStorageTest.DyingStream(BLOCK_SIZE + 1024)));
        assertFalse(storage.exists("01234567", "partial.bin"),
                "a blob with only uncommitted blocks must read as absent");
        String name = runPrefix + "/01234567/partial.bin";
        assertTrue(names(runPrefix + "/01234567/", true).contains(name),
                "the block really reached the service: the blob is enumerable as uncommitted");
        assertFalse(names(runPrefix + "/01234567/", false).contains(name),
                "and it is not enumerable as a committed blob");
    }

    @Test
    void stagedUploadCommitsEveryByteAgainstTheService() throws Exception {
        // The contract payloads all fit one Put Blob; this is the only place Put Block List
        // itself (id format, ordering, the commit) runs against the real service.
        AttachmentStorage storage = newStorage();
        byte[] content = new byte[BLOCK_SIZE + 4096];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        var stored = storage.store(new IncomingAttachment("01234567", "staged.bin", "application/octet-stream", -1),
                new ByteArrayInputStream(content));
        assertEquals(content.length, stored.bytesWritten());
        assertTrue(storage.exists("01234567", "staged.bin"));
        assertEquals(content.length, container.getBlobClient(stored.storageKey()).getProperties().getBlobSize());
        assertArrayEquals(content, container.getBlobClient(stored.storageKey()).downloadContent().toBytes(),
                "block order and the reused buffer must reproduce the stream exactly");
    }

    @Test
    void sameNameStoreOverwritesOnBothPaths() throws Exception {
        // Proves the options overloads really send no If-None-Match: small over nothing,
        // staged over small, small over staged. Size after each store is the witness.
        AttachmentStorage storage = newStorage();
        IncomingAttachment attachment = new IncomingAttachment("01234567", "dup.bin", null, -1);
        int[] sizes = {100, BLOCK_SIZE + 8, 200};
        for (int size : sizes) {
            var stored = storage.store(attachment, new ByteArrayInputStream(new byte[size]));
            assertEquals(size, container.getBlobClient(stored.storageKey()).getProperties().getBlobSize(),
                    "the latest store must win at size " + size);
        }
    }

    @Test
    void rwSasPassesTheProbeAndLeavesNoCommittedBlob() throws Exception {
        AzureBlobAttachmentStorage.forContainer(sasClient("rw"), runPrefix).verifyAccess();
        List<String> committed = names(runPrefix + "/" + AzureBlobAttachmentStorage.VERIFY_PREFIX, false);
        assertTrue(committed.isEmpty(), "the probe must commit nothing: " + committed);
        assertFalse(names(runPrefix + "/" + AzureBlobAttachmentStorage.VERIFY_PREFIX, true).isEmpty(),
                "the probe block really was staged");
    }

    @Test
    void cwOnlySasIsClassifiedAsNoPermissionAtTheReadStage() {
        // The case tsanetgit/Connect_SDK#73 flips: before it, this SAS passed go-live.
        AttachmentStorage storage = AzureBlobAttachmentStorage.forContainer(sasClient("cw"), runPrefix);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("no permission: read denied"), e.getMessage());
        assertTrue(names(runPrefix + "/" + AzureBlobAttachmentStorage.VERIFY_PREFIX, false).isEmpty(),
                "still nothing committed");
    }

    @Test
    void readOnlySasIsClassifiedAsNoPermissionAtTheWriteStage() {
        AttachmentStorage storage = AzureBlobAttachmentStorage.forContainer(sasClient("r"), runPrefix);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("no permission: write denied"), e.getMessage());
    }

    @Test
    void tamperedSignatureIsClassifiedAsWrongCredential() {
        String sas = container.generateSas(new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plusMinutes(15), BlobContainerSasPermission.parse("rw")));
        int sig = sas.indexOf("sig=") + 4;
        char flipped = sas.charAt(sig) == 'A' ? 'B' : 'A';
        String tampered = sas.substring(0, sig) + flipped + sas.substring(sig + 1);
        BlobContainerClient client = new BlobContainerClientBuilder()
                .endpoint(container.getBlobContainerUrl() + "?" + tampered).buildClient();
        AttachmentStorage storage = AzureBlobAttachmentStorage.forContainer(client, runPrefix);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("wrong credential"), e.getMessage());
        // The service's AuthenticationFailed detail must not echo the signature: wrap() and
        // classify() carry the SDK message into the chain.
        int end = tampered.indexOf('&', sig);
        String sigValue = tampered.substring(sig, end < 0 ? tampered.length() : end);
        String chain = fullChain(e);
        assertFalse(chain.contains(sigValue), "signature echoed in the exception chain");
        assertFalse(chain.contains(java.net.URLDecoder.decode(sigValue, java.nio.charset.StandardCharsets.UTF_8)),
                "decoded signature echoed in the exception chain");
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
    void hostileFileNamesRoundTripUnderTheirLiteralEncodedName() throws Exception {
        AttachmentStorage storage = newStorage();
        String[] hostile = {
                "../../etc/passwd", "a/b.txt", "trailing.", "trailing/", "back\\slash.txt",
                "report#1.txt", "50%.log", "spaces and üñïçode.txt", "q?mark.txt", ".verify-fake",
        };
        for (String fileName : hostile) {
            IncomingAttachment attachment = new IncomingAttachment("01234567", fileName, null, -1);
            var stored = storage.store(attachment, new ByteArrayInputStream("hostile".getBytes()));
            assertTrue(storage.exists("01234567", fileName), "not visible under the same name: " + fileName);
            List<String> committed = names(runPrefix + "/01234567/", false);
            assertTrue(committed.contains(stored.storageKey()),
                    "the blob is not where the adapter says it is (normalized?): " + fileName + " -> " + committed);
            System.out.println("[hostile-name] " + fileName + " -> " + stored.storageKey().substring(runPrefix.length() + 1));
        }
    }

    private BlobContainerClient sasClient(String permissions) {
        String sas = container.generateSas(new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plusMinutes(15), BlobContainerSasPermission.parse(permissions)));
        return new BlobContainerClientBuilder()
                .endpoint(container.getBlobContainerUrl() + "?" + sas).buildClient();
    }

    private List<String> names(String prefix, boolean includeUncommitted) {
        ListBlobsOptions options = new ListBlobsOptions().setPrefix(prefix)
                .setDetails(new BlobListDetails().setRetrieveUncommittedBlobs(includeUncommitted));
        List<String> names = new ArrayList<>();
        for (BlobItem item : container.listBlobs(options, null)) {
            names.add(item.getName());
        }
        return names;
    }
}

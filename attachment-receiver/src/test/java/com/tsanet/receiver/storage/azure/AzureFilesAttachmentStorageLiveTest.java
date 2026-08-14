package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.azure.storage.file.share.ShareClient;
import com.azure.storage.file.share.ShareClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static com.tsanet.receiver.storage.azure.AzureFilesAttachmentStorage.CHUNK_SIZE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The issue-mandated real-share acceptance: the shared contract test bound to a live
 * Azure file share, plus abort-after-partial-commit — on Azure Files the partial state
 * IS a visible temp file, so this asserts the final name never appears and no
 * {@code .incoming-} residue survives the failure.
 *
 * <p>Gated on {@code AZURE_FILES_CONNECTION_STRING} + {@code AZURE_FILES_CONTRACT_TEST_SHARE}.
 * Each instance works under a random directory prefix and tears it down afterward.
 */
@EnabledIfEnvironmentVariable(named = "AZURE_FILES_CONNECTION_STRING", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AZURE_FILES_CONTRACT_TEST_SHARE", matches = ".+")
class AzureFilesAttachmentStorageLiveTest extends AttachmentStorageContractTest {

    private final String runPrefix = "contract-" + UUID.randomUUID();
    private final ShareClient share = new ShareClientBuilder()
            .connectionString(System.getenv("AZURE_FILES_CONNECTION_STRING"))
            .shareName(System.getenv("AZURE_FILES_CONTRACT_TEST_SHARE"))
            .buildClient();

    @Override
    protected AttachmentStorage newStorage() {
        return AzureFilesAttachmentStorage.forShare(share, runPrefix);
    }

    @AfterEach
    void tearDownPrefix() {
        var root = share.getDirectoryClient(runPrefix);
        if (Boolean.TRUE.equals(root.exists())) {
            deleteRecursively(root);
        }
    }

    private void deleteRecursively(com.azure.storage.file.share.ShareDirectoryClient dir) {
        dir.listFilesAndDirectories().forEach(item -> {
            if (item.isDirectory()) {
                deleteRecursively(dir.getSubdirectoryClient(item.getName()));
            } else {
                dir.getFileClient(item.getName()).delete();
            }
        });
        dir.delete();
    }

    @Test
    void abortAfterPartialCommitLeavesNothingVisible() throws Exception {
        AttachmentStorage storage = newStorage();
        IncomingAttachment attachment =
                new IncomingAttachment("01234567", "partial.bin", null, -1);
        // Fail after one full 4 MiB chunk has really been committed to the temp file.
        InputStream diesAfterOneChunk = new InputStream() {
            private long served;

            @Override
            public int read() throws IOException {
                if (served < CHUNK_SIZE + 1024) {
                    served++;
                    return 'x';
                }
                throw new IOException("stream died after a committed chunk (simulated)");
            }
        };
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment, diesAfterOneChunk));
        assertFalse(storage.exists("01234567", "partial.bin"),
                "the final name must never appear for a failed store");
        var caseDir = share.getDirectoryClient(runPrefix).getSubdirectoryClient("01234567");
        if (Boolean.TRUE.equals(caseDir.exists())) {
            caseDir.listFilesAndDirectories().forEach(item ->
                    assertFalse(item.getName().startsWith(AzureFilesAttachmentStorage.TEMP_PREFIX),
                            "no .incoming- residue may survive: " + item.getName()));
        }
    }
}

package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.tsanet.receiver.storage.StoredAttachment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static com.tsanet.receiver.storage.azure.AzureFilesAttachmentStorage.CHUNK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline behavior against the strict double, which enforces the Azure semantics the
 * adapter must survive (visibility from create, fixed sizes, 4 MiB ranges, real
 * traversal rejection). Live-share coverage is {@code AzureFilesAttachmentStorageLiveTest}.
 */
class AzureFilesAttachmentStorageTest {

    private InMemoryAzureShare share;
    private AzureFilesAttachmentStorage storage;

    @BeforeEach
    void setUp() {
        share = new InMemoryAzureShare();
        storage = new AzureFilesAttachmentStorage(share, "tenants/acme");
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
    void storeLandsUnderThePrefixedEncodedPath() throws Exception {
        StoredAttachment stored = storage.store(attachment("diag.log"),
                new ByteArrayInputStream(bytes(10)));
        assertEquals("tenants/acme/01234567/diag.log", stored.storageKey());
        assertEquals(10, stored.bytesWritten());
        assertArrayEquals(bytes(10), share.files.get("tenants/acme/01234567/diag.log"));
        assertFalse(share.anyTempResidue(), "the temp file must be renamed away");
    }

    @Test
    void multiChunkFilesGrowAndCountEveryByte() throws Exception {
        int size = CHUNK_SIZE + 100;
        StoredAttachment stored = storage.store(attachment("big.bin"),
                new ByteArrayInputStream(bytes(size)));
        assertEquals(size, stored.bytesWritten());
        assertEquals(size, share.files.get(stored.storageKey()).length);
    }

    @Test
    void emptyStreamStoresAZeroByteFile() throws Exception {
        StoredAttachment stored = storage.store(attachment("empty.txt"),
                new ByteArrayInputStream(new byte[0]));
        assertEquals(0, stored.bytesWritten());
        assertEquals(0, share.files.get(stored.storageKey()).length);
    }

    @Test
    void streamDeathLeavesNoFinalFileAndNoTempResidue() {
        InputStream dies = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                if (served < CHUNK_SIZE + 10) {
                    served++;
                    return 'x';
                }
                throw new IOException("stream died (simulated)");
            }
        };
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("dies.bin"), dies));
        assertFalse(share.files.containsKey("tenants/acme/01234567/dies.bin"),
                "the final name must never exist for a failed store");
        assertFalse(share.anyTempResidue(), "the temp file must be cleaned up");
    }

    @Test
    void tempCleanupFailureRidesAsSuppressed() {
        share.failCreateWith = null;
        InputStream dies = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("stream died immediately (simulated)");
            }
        };
        share.failDeleteWith = () -> new IllegalStateException("delete also died (simulated)");
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("worse.bin"), dies));
        assertTrue(e.getMessage().contains("stream failed mid-read"), e.getMessage());
        assertEquals(1, e.getSuppressed().length, "cleanup failure must ride as suppressed");
    }

    @Test
    void ambiguousRenameThatActuallyCommittedIsReportedAsSuccess() throws Exception {
        share.failRenameWith = () -> new IllegalStateException("rename timed out (simulated)");
        share.renameCommitsDespiteFailure = true;
        StoredAttachment stored = storage.store(attachment("ambiguous.bin"),
                new ByteArrayInputStream(bytes(20)));
        assertEquals(20, stored.bytesWritten());
        assertTrue(share.files.containsKey(stored.storageKey()));
    }

    @Test
    void ambiguousRenameWithNothingCommittedThrowsAndCleansTheTemp() {
        share.failRenameWith = () -> new IllegalStateException("rename rejected (simulated)");
        share.renameCommitsDespiteFailure = false;
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("stuck.bin"), new ByteArrayInputStream(bytes(20))));
        assertFalse(share.files.containsKey("tenants/acme/01234567/stuck.bin"));
        assertFalse(share.anyTempResidue());
    }

    @Test
    void sameNameStoreOverwrites() throws Exception {
        storage.store(attachment("same.txt"), new ByteArrayInputStream(bytes(5)));
        storage.store(attachment("same.txt"), new ByteArrayInputStream(bytes(9)));
        assertEquals(9, share.files.get("tenants/acme/01234567/same.txt").length,
                "rename-with-replace means overwrite, the documented same-name policy");
    }

    @Test
    void traversalFileNamesAreEncodedBeforeTheyReachTheShare() throws Exception {
        // The strict double throws on any raw '..' or '\' component, so this passing
        // proves the encoder neutralized the name, not that the double is lenient.
        StoredAttachment stored = storage.store(attachment("../../etc/passwd"),
                new ByteArrayInputStream(bytes(4)));
        assertTrue(stored.storageKey().startsWith("tenants/acme/01234567/"),
                "the hostile name stays a single component inside the case directory: "
                        + stored.storageKey());
        assertTrue(storage.exists("01234567", "../../etc/passwd"),
                "exists must encode identically to store");
    }

    @Test
    void leadingDotFileNamesCannotImpersonateTempFiles() throws Exception {
        StoredAttachment stored = storage.store(attachment(".incoming-fake"),
                new ByteArrayInputStream(bytes(4)));
        assertFalse(stored.storageKey()
                        .substring(stored.storageKey().lastIndexOf('/') + 1).startsWith("."),
                "an encoded name can never start with a dot: " + stored.storageKey());
        assertFalse(share.anyTempResidue(),
                "a real attachment must never be classifiable as temp residue");
        assertTrue(storage.exists("01234567", ".incoming-fake"));
    }

    @Test
    void oversizedNamesSurfaceThroughTheCheckedExceptionNotARawEscape() {
        // The 255-cap failure is remote-triggerable (fileName is hostile input), so it
        // must arrive as the SPI's checked exception, never an unchecked escape.
        String huge = "x".repeat(300);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment(huge), new ByteArrayInputStream(bytes(1))));
        assertTrue(e.getMessage().contains("255"), e.getMessage());
        assertThrows(AttachmentStorageException.class,
                () -> storage.exists("01234567", huge));
    }

    @Test
    void existsWrapsShareFailuresInsteadOfAnsweringFalse() {
        share.failExistsWith = () -> new IllegalStateException("share unreachable (simulated)");
        assertThrows(AttachmentStorageException.class,
                () -> storage.exists("01234567", "any.txt"),
                "an outage must throw, never read as object-absent");
    }

    @Test
    void verifyAccessCleansItsSentinelOnSuccessAndFailure() throws Exception {
        storage.verifyAccess();
        assertTrue(share.files.isEmpty(), "sentinel must be deleted after a passing probe");

        share.failDownloadWith = () -> new IllegalStateException("read stage died (simulated)");
        assertThrows(AttachmentStorageException.class, () -> storage.verifyAccess());
        assertTrue(share.files.isEmpty(), "sentinel must be deleted after a failing probe");
    }

    @Test
    void verifyAccessConnectivityFailureNamesTheStage() {
        share.failCreateWith = () -> new IllegalStateException("connection refused (simulated)");
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.verifyAccess());
        assertTrue(e.getMessage().contains("connectivity"), e.getMessage());
        assertTrue(e.getMessage().contains("write"), e.getMessage());
    }
}

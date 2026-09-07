package com.tsanet.receiver.storage.azure;

import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageContractTest;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.IncomingAttachment;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static com.tsanet.receiver.storage.azure.AzureBlobAttachmentStorage.BLOCK_SIZE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared contract against the strict in-memory double, plus the Blob-specific
 * edges the contract cannot reach: the single-shot/staged split, abandon-not-commit on
 * failure, the ambiguous-commit policy, and the three verify classifications driven by
 * real {@link BlobStorageException}s carrying the service's error-code header (the
 * classification order matters, because Azure answers {@code AuthenticationFailed} with
 * 403). The uncommitted-block invisibility the double encodes is an assumption here; the
 * live test is what proves it against the service.
 */
class AzureBlobAttachmentStorageTest extends AttachmentStorageContractTest {

    @Override
    protected AttachmentStorage newStorage() {
        return new AzureBlobAttachmentStorage(new InMemoryAzureBlobContainer(), "tenant-container", "tenants/acme");
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
    void smallFileUsesSinglePutBlob() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        byte[] content = bytes(1024);
        storage.store(attachment("small.bin"), new ByteArrayInputStream(content));
        assertEquals(0, blobs.stagedBlocks, "a file that fits one buffer never stages blocks");
        assertEquals(0, blobs.commits);
        assertArrayEquals(content, blobs.contentOf("01234567/small.bin"));
    }

    @Test
    void emptyFileStoresAZeroByteBlob() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        var stored = storage.store(attachment("empty.bin"), new ByteArrayInputStream(new byte[0]));
        assertEquals(0, stored.bytesWritten());
        assertTrue(storage.exists("01234567", "empty.bin"));
    }

    @Test
    void fileLargerThanOneBlockIsStagedAndCommittedWithEveryByte() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        byte[] content = bytes(BLOCK_SIZE + 4096);
        var stored = storage.store(attachment("big.bin"), new ByteArrayInputStream(content));
        assertEquals(content.length, stored.bytesWritten());
        assertEquals(2, blobs.stagedBlocks);
        assertEquals(1, blobs.commits);
        assertArrayEquals(content, blobs.contentOf("01234567/big.bin"),
                "block order and the reused buffer must reproduce the stream exactly");
        assertFalse(blobs.hasUncommittedBlocks("01234567/big.bin"), "a commit consumes the staged blocks");
    }

    @Test
    void eofExactlyOnBlockBoundaryCommitsOneBlock() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        var stored = storage.store(attachment("exact.bin"), new ByteArrayInputStream(bytes(BLOCK_SIZE)));
        assertEquals(BLOCK_SIZE, stored.bytesWritten());
        assertEquals(1, blobs.stagedBlocks, "a zero-length trailing read must not stage an empty block");
        assertEquals(BLOCK_SIZE, blobs.contentOf("01234567/exact.bin").length);
    }

    @Test
    void streamFailureMidUploadAbandonsAndLeavesNothingVisible() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        // Yield more than one block, then die: forces the staged path, then fails it.
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("truncated.bin"), new DyingStream(BLOCK_SIZE + 1024)));
        assertEquals(0, blobs.commits, "a mid-stream failure must abandon, never commit");
        assertTrue(blobs.hasUncommittedBlocks("01234567/truncated.bin"),
                "the first block really was staged; invisibility is the property under test");
        assertNull(blobs.contentOf("01234567/truncated.bin"));
        assertFalse(storage.exists("01234567", "truncated.bin"));
    }

    @Test
    void putBlobFailureSurfacesThroughTheContractAndLeavesNothingVisible() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failPutBlobWith = () -> new IllegalStateException("put blob rejected (simulated)");
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("small.bin"), new ByteArrayInputStream(bytes(16))));
        assertTrue(e.getMessage().contains("put blob"), e.getMessage());
        assertFalse(storage.exists("01234567", "small.bin"));
    }

    @Test
    void ambiguousCommitProbeFailureRidesAsSuppressedOnTheCommitFailure() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failCommitWith = () -> new IllegalStateException("commit timed out (simulated)");
        blobs.failCommittedBlockIdsWith = () -> new IllegalStateException("block list unreachable (simulated)");
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("probe.bin"), new ByteArrayInputStream(bytes(BLOCK_SIZE + 8))));
        // Fails closed on the commit failure; the probe's own failure is not lost.
        assertTrue(e.getMessage().contains("commit"), e.getMessage());
        assertEquals("commit timed out (simulated)", e.getCause().getMessage());
        assertEquals(1, e.getCause().getSuppressed().length);
        assertEquals("block list unreachable (simulated)", e.getCause().getSuppressed()[0].getMessage());
    }

    @Test
    void stageFailureAbandonsWithoutACommit() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failStageBlockWith = () -> new IllegalStateException("stage rejected (simulated)");
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("rejected.bin"), new ByteArrayInputStream(bytes(BLOCK_SIZE + 8))));
        assertEquals(0, blobs.commits);
        assertFalse(storage.exists("01234567", "rejected.bin"));
    }

    @Test
    void ambiguousCommitThatActuallyCommittedIsReportedAsSuccess() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failCommitWith = () -> new IllegalStateException("commit timed out after commit (simulated)");
        blobs.commitCommitsDespiteFailure = true;
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        byte[] content = bytes(BLOCK_SIZE + 8);
        var stored = storage.store(attachment("ambiguous.bin"), new ByteArrayInputStream(content));
        assertEquals(content.length, stored.bytesWritten(),
                "a commit that landed server-side is the success it was");
    }

    @Test
    void ambiguousCommitOverAPreExistingSameSizeBlobThrows() throws Exception {
        // The wrong-but-well-formed case for a size-based resolution: an older blob of the
        // same name and the same length is already there, and this commit did NOT land.
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        byte[] content = bytes(BLOCK_SIZE + 8);
        storage.store(attachment("same-size.bin"), new ByteArrayInputStream(content));
        blobs.failCommitWith = () -> new IllegalStateException("commit rejected, nothing committed (simulated)");
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("same-size.bin"), new ByteArrayInputStream(content)),
                "an older same-size blob must not be mistaken for this call's commit");
        assertArrayEquals(content, blobs.contentOf("01234567/same-size.bin"), "the older blob is untouched");
    }

    @Test
    void ambiguousCommitWithNothingCommittedThrows() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failCommitWith = () -> new IllegalStateException("commit rejected, nothing committed (simulated)");
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment("lost.bin"), new ByteArrayInputStream(bytes(BLOCK_SIZE + 8))));
        assertFalse(storage.exists("01234567", "lost.bin"));
    }

    @Test
    void sameNameStoreOverwrites() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        storage.store(attachment("dup.bin"), new ByteArrayInputStream(bytes(100)));
        storage.store(attachment("dup.bin"), new ByteArrayInputStream(bytes(200)));
        assertEquals(200, blobs.contentOf("01234567/dup.bin").length);
    }

    @Test
    void traversalFileNamesAreEncodedBeforeTheyReachTheContainer() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", "tenants/acme");
        String hostile = "../../etc/passwd";
        // The double throws on any raw traversal component, so passing proves the encoder
        // neutralized the name, not that the fake was lenient.
        storage.store(attachment(hostile), new ByteArrayInputStream(bytes(16)));
        assertTrue(storage.exists("01234567", hostile));
        assertNotNull(blobs.contentOf("tenants/acme/01234567/%2E.%2F..%2Fetc%2Fpasswd"),
                "leading dot and slashes encoded, interior dots pass through: one traversal-inert segment");
        storage.store(attachment("back\\slash."), new ByteArrayInputStream(bytes(16)));
        assertNotNull(blobs.contentOf("tenants/acme/01234567/back%5Cslash%2E"));
    }

    @Test
    void leadingDotFileNamesCannotImpersonateTheProbeNamespace() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        storage.store(attachment(AzureBlobAttachmentStorage.VERIFY_PREFIX + "fake"), new ByteArrayInputStream(bytes(8)));
        assertNotNull(blobs.contentOf("01234567/%2Everify-fake"));
        assertNull(blobs.contentOf("01234567/" + AzureBlobAttachmentStorage.VERIFY_PREFIX + "fake"));
    }

    @Test
    void oversizedNamesSurfaceThroughTheCheckedExceptionNotARawEscape() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        String tooLong = "x".repeat(300);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment(tooLong), new ByteArrayInputStream(bytes(8))));
        assertThrows(AttachmentStorageException.class, () -> storage.exists("01234567", tooLong));
    }

    @Test
    void verifyAccessStagesOneUncommittedBlockAndCommitsNothing() throws Exception {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", "tenants/acme");
        storage.verifyAccess();
        assertEquals(0, blobs.committedCount(), "the probe must leave no committed blob");
        assertEquals(1, blobs.stagedBlocks);
        assertEquals(1, blobs.uncommittedNames().size());
        assertTrue(blobs.uncommittedNames().get(0).startsWith("tenants/acme/" + AzureBlobAttachmentStorage.VERIFY_PREFIX),
                blobs.uncommittedNames().get(0));
    }

    @Test
    void verifyAccessClassifiesWrongCredentialByErrorCodeDespiteThe403() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failStageBlockWith = () -> blobError(403, BlobErrorCode.AUTHENTICATION_FAILED);
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "locked", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("wrong credential"), e.getMessage());
    }

    @Test
    void verifyAccessClassifiesNoPermission() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failStageBlockWith = () -> blobError(403, BlobErrorCode.AUTHORIZATION_PERMISSION_MISMATCH);
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "locked", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("no permission"), e.getMessage());
        assertTrue(e.getMessage().contains("locked"), e.getMessage());

        // A bare 403 with no recognized code is still no-permission, never wrong-credential.
        blobs.failStageBlockWith = () -> blobError(403, null);
        AttachmentStorageException bare = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(bare.getMessage().contains("no permission"), bare.getMessage());
    }

    @Test
    void verifyAccessClassifiesWrongTarget() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failStageBlockWith = () -> blobError(404, BlobErrorCode.CONTAINER_NOT_FOUND);
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "ghost", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("wrong target"), e.getMessage());
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void verifyAccessConnectivityFailureIsNamedAsSuch() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failStageBlockWith = () -> new IllegalStateException("connection refused (simulated)");
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        AttachmentStorageException e = assertThrows(AttachmentStorageException.class, storage::verifyAccess);
        assertTrue(e.getMessage().contains("connectivity"), e.getMessage());
    }

    @Test
    void existsThrowsWhenTheBackendCannotAnswer() {
        InMemoryAzureBlobContainer blobs = new InMemoryAzureBlobContainer();
        blobs.failSizeOrAbsentWith = () -> blobError(500, null);
        AzureBlobAttachmentStorage storage = new AzureBlobAttachmentStorage(blobs, "c", null);
        // The SPI forbids answering false on an indeterminate check.
        assertThrows(AttachmentStorageException.class, () -> storage.exists("01234567", "x.bin"));
    }

    @Test
    void blockIdsAreFixedLengthBase64UnderTheServiceCap() {
        String first = AzureBlobAttachmentStorage.blockId("0f0e0d0c-0b0a-0908-0706-050403020100", 0);
        String last = AzureBlobAttachmentStorage.blockId("0f0e0d0c-0b0a-0908-0706-050403020100", 49999);
        assertEquals(first.length(), last.length());
        assertEquals(42, java.util.Base64.getDecoder().decode(last).length, "42 bytes decoded, cap is 64");
    }

    /**
     * A real {@link BlobStorageException} whose error code travels the way the SDK reads
     * it: from the {@code x-ms-error-code} response header. A null code models a response
     * with no service error code at all.
     */
    static BlobStorageException blobError(int status, BlobErrorCode code) {
        HttpHeaders headers = new HttpHeaders();
        if (code != null) {
            headers.set("x-ms-error-code", code.toString());
        }
        HttpRequest request = new HttpRequest(HttpMethod.PUT, "https://example.invalid/container/blob");
        HttpResponse response = new HttpResponse(request) {
            @Override
            public int getStatusCode() {
                return status;
            }

            @Override
            public String getHeaderValue(String name) {
                return headers.getValue(com.azure.core.http.HttpHeaderName.fromString(name));
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public Flux<ByteBuffer> getBody() {
                return Flux.empty();
            }

            @Override
            public Mono<byte[]> getBodyAsByteArray() {
                return Mono.just(new byte[0]);
            }

            @Override
            public Mono<String> getBodyAsString() {
                return Mono.just("");
            }

            @Override
            public Mono<String> getBodyAsString(Charset charset) {
                return Mono.just("");
            }
        };
        return new BlobStorageException("simulated HTTP " + status, response, null);
    }

    /** Yields {@code deathAt} bytes, then fails: the wrong-but-well-formed input for a store path. */
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

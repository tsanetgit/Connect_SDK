package com.tsanet.receiver.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every {@link AttachmentStorage} adapter must pass; an adapter binds itself
 * by extending this class and implementing {@link #newStorage()}. Deliberately absent:
 * any assertion about storing the same (caseNumber, fileName) twice — that policy is an
 * open contract question (tsanetgit/Connect-API-Code#140, question 2) and pinning it here
 * would encode an answer we do not have.
 */
public abstract class AttachmentStorageContractTest {

    /** A fresh, empty storage instance per test. */
    protected abstract AttachmentStorage newStorage() throws Exception;

    private static final byte[] CONTENT = "attachment bytes for the contract test".getBytes(StandardCharsets.UTF_8);

    private static IncomingAttachment knownLength() {
        return new IncomingAttachment("01234567", "diag.log", "text/plain", CONTENT.length);
    }

    @Test
    void storeThenExists() throws Exception {
        AttachmentStorage storage = newStorage();
        StoredAttachment stored = storage.store(knownLength(), new ByteArrayInputStream(CONTENT));
        assertFalse(stored.storageKey().isBlank(), "storageKey must identify the object");
        assertTrue(storage.exists("01234567", "diag.log"), "stored object must be visible");
    }

    @Test
    void bytesWrittenMatchesStreamedLength() throws Exception {
        AttachmentStorage storage = newStorage();
        StoredAttachment stored = storage.store(knownLength(), new ByteArrayInputStream(CONTENT));
        assertEquals(CONTENT.length, stored.bytesWritten());
    }

    @Test
    void unknownLengthStreamStores() throws Exception {
        AttachmentStorage storage = newStorage();
        IncomingAttachment unknown = new IncomingAttachment("01234567", "chunked.bin", null, -1);
        StoredAttachment stored = storage.store(unknown, new ByteArrayInputStream(CONTENT));
        assertEquals(CONTENT.length, stored.bytesWritten(),
                "bytesWritten must be the real count even when the incoming length was unknown");
        assertTrue(storage.exists("01234567", "chunked.bin"));
    }

    @Test
    void existsIsFalseForUnknownFile() throws Exception {
        AttachmentStorage storage = newStorage();
        assertFalse(storage.exists("01234567", "never-stored.txt"));
    }

    @Test
    void storeDoesNotCloseTheCallersStream() throws Exception {
        AttachmentStorage storage = newStorage();
        var tracking = new CloseTrackingStream(new ByteArrayInputStream(CONTENT));
        storage.store(knownLength(), tracking);
        assertFalse(tracking.closed, "the caller owns the stream; store must not close it");
    }

    @Test
    void failedStoreLeavesNoPartialVisibility() throws Exception {
        AttachmentStorage storage = newStorage();
        IncomingAttachment attachment = new IncomingAttachment("01234567", "truncated.dat", null, -1);
        assertThrows(AttachmentStorageException.class,
                () -> storage.store(attachment, new MidReadFailingStream()));
        assertFalse(storage.exists("01234567", "truncated.dat"),
                "a failed store must abort cleanly, never leave a half-written object visible");
    }

    @Test
    void verifyAccessSucceedsOnAHealthyBackend() throws Exception {
        newStorage().verifyAccess();
    }

    private static final class CloseTrackingStream extends FilterInputStream {
        boolean closed;

        CloseTrackingStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** Yields a few bytes, then fails: the wrong-but-well-formed input for a store path. */
    private static final class MidReadFailingStream extends InputStream {
        private int served;

        @Override
        public int read() throws IOException {
            if (served++ < 4) {
                return 'x';
            }
            throw new IOException("stream failed mid-read (simulated)");
        }
    }
}

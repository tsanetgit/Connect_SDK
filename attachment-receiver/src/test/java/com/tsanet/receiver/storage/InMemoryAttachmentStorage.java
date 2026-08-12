package com.tsanet.receiver.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double: the reference {@link AttachmentStorage} semantics with no backend. Buffers
 * in memory, which the SPI forbids for real adapters — acceptable only because this class
 * exists to make the contract test and future callers testable, never to hold real files.
 */
public final class InMemoryAttachmentStorage implements AttachmentStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public StoredAttachment store(IncomingAttachment attachment, InputStream content)
            throws AttachmentStorageException {
        String key = key(attachment.caseNumber(), attachment.fileName());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try {
            int read;
            while ((read = content.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        } catch (IOException e) {
            throw new AttachmentStorageException(
                    "stream failed mid-read for " + key + "; nothing stored", e);
        }
        byte[] bytes = buffer.toByteArray();
        objects.put(key, bytes);
        return new StoredAttachment(key, bytes.length);
    }

    @Override
    public boolean exists(String caseNumber, String fileName) {
        return objects.containsKey(key(caseNumber, fileName));
    }

    @Override
    public void verifyAccess() {
        // No backend to probe: the in-memory map is always reachable and writable.
    }

    /** Visible for callers' assertions in future tests, not part of the SPI. */
    public byte[] contentOf(String caseNumber, String fileName) {
        byte[] bytes = objects.get(key(caseNumber, fileName));
        return bytes == null ? null : bytes.clone();
    }

    private static String key(String caseNumber, String fileName) {
        return caseNumber + "/" + fileName;
    }
}

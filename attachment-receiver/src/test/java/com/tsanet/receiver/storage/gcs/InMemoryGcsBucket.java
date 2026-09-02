package com.tsanet.receiver.storage.gcs;

import com.google.cloud.storage.StorageException;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Strict in-memory {@link GcsBucket} that enforces the GCS semantics the adapter must
 * survive: a resumable upload is invisible until {@link Upload#finish()}, an
 * {@link Upload#abandon() abandoned} one never appears, and a missing object reads as
 * {@code -1} rather than as an error. Failure-injection fields let a test make one
 * operation throw a {@link StorageException} with a chosen HTTP code, which is how the
 * verify-classification and ambiguous-finish paths get exercised without a backend.
 */
final class InMemoryGcsBucket implements GcsBucket {

    private final Map<String, byte[]> objects = new HashMap<>();

    /** When set, the named operation throws this instead of succeeding. */
    StorageException failCreate;
    StorageException failStartResumable;
    StorageException failFinish;
    StorageException failSizeOrAbsent;
    StorageException failDownload;

    /** Ambiguous-finish: commit the object, then throw {@link #failFinish}. */
    boolean finishCommitsBeforeFailing;

    /** Observed for assertions: a failed store must abandon, never finish. */
    int abandonCount;

    @Override
    public void create(String key, String contentType, byte[] bytes) {
        if (failCreate != null) {
            throw failCreate;
        }
        objects.put(key, bytes.clone());
    }

    @Override
    public Upload startResumable(String key, String contentType) {
        if (failStartResumable != null) {
            throw failStartResumable;
        }
        return new Upload() {
            private final ByteArrayOutputStream staged = new ByteArrayOutputStream();

            @Override
            public void write(byte[] buffer, int length) {
                staged.write(buffer, 0, length);
            }

            @Override
            public void finish() {
                if (failFinish != null) {
                    if (finishCommitsBeforeFailing) {
                        objects.put(key, staged.toByteArray());
                    }
                    throw failFinish;
                }
                // Visible only now: the resumable object appears on finalize.
                objects.put(key, staged.toByteArray());
            }

            @Override
            public void abandon() {
                abandonCount++;
                // Discarded: staged bytes never reach the visible map.
            }
        };
    }

    @Override
    public long sizeOrAbsent(String key) {
        if (failSizeOrAbsent != null) {
            throw failSizeOrAbsent;
        }
        byte[] bytes = objects.get(key);
        return bytes == null ? -1 : bytes.length;
    }

    @Override
    public byte[] download(String key) {
        if (failDownload != null) {
            throw failDownload;
        }
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new StorageException(404, "no such object: " + key);
        }
        return bytes.clone();
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    /** For assertions: the stored bytes, or {@code null} if the object is not visible. */
    byte[] contentOf(String key) {
        byte[] bytes = objects.get(key);
        return bytes == null ? null : bytes.clone();
    }
}

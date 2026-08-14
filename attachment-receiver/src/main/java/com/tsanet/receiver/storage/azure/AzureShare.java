package com.tsanet.receiver.storage.azure;

/**
 * The adapter's seam to one Azure file share: a 1:1 wrapper over the SDK calls the
 * adapter uses, and nothing more. Exists because the SDK's clients are final classes;
 * offline tests run against a strict in-memory implementation that enforces Azure's
 * semantics (fixed size at create, 4 MiB range cap, visibility from create, rename
 * semantics), and {@link SdkAzureShare} stays logic-free so the live contract run
 * genuinely covers it. Package-private on purpose: this is not a second SPI.
 *
 * <p>All paths are share-root-relative, already-encoded strings. Implementations throw
 * their runtime exceptions through; the adapter owns wrapping.
 */
interface AzureShare {

    /** Creates every level of the directory path that does not yet exist. */
    void ensureDirectory(String directoryPath);

    /** Creates a file of the given size; on Azure Files it is VISIBLE from this moment. */
    void createFile(String filePath, long size);

    void resizeFile(String filePath, long newSize);

    /** Writes bytes at offset; the range must lie inside the file's current size, and at most 4 MiB. */
    void uploadRange(String filePath, long offset, byte[] bytes);

    /** Renames within the share; both paths are share-root-relative. */
    void renameFile(String fromPath, String toPath, boolean replaceIfExists);

    /** True when the file exists; a missing file (or parent) is false, any other failure throws. */
    boolean fileExists(String filePath);

    byte[] downloadFile(String filePath);

    void deleteFile(String filePath);
}

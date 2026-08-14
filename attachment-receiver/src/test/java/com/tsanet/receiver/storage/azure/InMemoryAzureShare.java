package com.tsanet.receiver.storage.azure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A strict {@link AzureShare} double. It deliberately enforces the Azure Files
 * semantics the adapter exists to survive — a permissive fake here would pass a broken
 * adapter:
 * <ul>
 *   <li>files are visible (and downloadable) from {@code createFile}, at their fixed size;</li>
 *   <li>a range write beyond the file's current size is rejected (no append);</li>
 *   <li>a range write over 4 MiB is rejected;</li>
 *   <li>path components may not be empty, {@code .}, {@code ..}, or contain a backslash
 *       (the traversal shapes the encoder must have neutralized before the seam);</li>
 *   <li>creating a file requires its parent directory; rename requires the source and
 *       honors {@code replaceIfExists}.</li>
 * </ul>
 */
final class InMemoryAzureShare implements AzureShare {

    final Map<String, byte[]> files = new HashMap<>();
    final Set<String> directories = new HashSet<>();

    Supplier<RuntimeException> failRenameWith;
    boolean renameCommitsDespiteFailure;
    Supplier<RuntimeException> failDeleteWith;
    Supplier<RuntimeException> failExistsWith;
    Supplier<RuntimeException> failCreateWith;
    Supplier<RuntimeException> failDownloadWith;

    @Override
    public void ensureDirectory(String directoryPath) {
        validate(directoryPath);
        StringBuilder walked = new StringBuilder();
        for (String component : directoryPath.split("/")) {
            walked.append(walked.isEmpty() ? "" : "/").append(component);
            directories.add(walked.toString());
        }
    }

    @Override
    public void createFile(String filePath, long size) {
        validate(filePath);
        if (failCreateWith != null) {
            throw failCreateWith.get();
        }
        String parent = parentOf(filePath);
        if (!parent.isEmpty() && !directories.contains(parent)) {
            throw new IllegalStateException("ParentNotFound: " + parent);
        }
        // Azure semantics: the file is VISIBLE from this moment, at its full fixed size.
        files.put(filePath, new byte[(int) size]);
    }

    @Override
    public void resizeFile(String filePath, long newSize) {
        byte[] existing = requireFile(filePath);
        files.put(filePath, Arrays.copyOf(existing, (int) newSize));
    }

    @Override
    public void uploadRange(String filePath, long offset, byte[] bytes) {
        if (bytes.length > AzureFilesAttachmentStorage.CHUNK_SIZE) {
            throw new IllegalStateException("InvalidRange: range writes cap at 4 MiB, got " + bytes.length);
        }
        byte[] existing = requireFile(filePath);
        if (offset + bytes.length > existing.length) {
            throw new IllegalStateException("InvalidRange: write past the fixed file size (no append)");
        }
        System.arraycopy(bytes, 0, existing, (int) offset, bytes.length);
    }

    @Override
    public void renameFile(String fromPath, String toPath, boolean replaceIfExists) {
        validate(toPath);
        byte[] source = requireFile(fromPath);
        if (failRenameWith != null) {
            if (renameCommitsDespiteFailure) {
                files.remove(fromPath);
                files.put(toPath, source);
            }
            throw failRenameWith.get();
        }
        if (files.containsKey(toPath) && !replaceIfExists) {
            throw new IllegalStateException("ResourceAlreadyExists: " + toPath);
        }
        files.remove(fromPath);
        files.put(toPath, source);
    }

    @Override
    public boolean fileExists(String filePath) {
        if (failExistsWith != null) {
            throw failExistsWith.get();
        }
        return files.containsKey(filePath);
    }

    @Override
    public byte[] downloadFile(String filePath) {
        if (failDownloadWith != null) {
            throw failDownloadWith.get();
        }
        return requireFile(filePath).clone();
    }

    @Override
    public void deleteFile(String filePath) {
        if (failDeleteWith != null) {
            throw failDeleteWith.get();
        }
        requireFile(filePath);
        files.remove(filePath);
    }

    boolean anyTempResidue() {
        return files.keySet().stream()
                .anyMatch(p -> p.substring(p.lastIndexOf('/') + 1)
                        .startsWith(AzureFilesAttachmentStorage.TEMP_PREFIX));
    }

    private byte[] requireFile(String filePath) {
        byte[] existing = files.get(filePath);
        if (existing == null) {
            throw new IllegalStateException("ResourceNotFound: " + filePath);
        }
        return existing;
    }

    private static String parentOf(String filePath) {
        int slash = filePath.lastIndexOf('/');
        return slash < 0 ? "" : filePath.substring(0, slash);
    }

    private static void validate(String path) {
        for (String component : path.split("/")) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                    || component.contains("\\")) {
                throw new IllegalStateException(
                        "InvalidResourceName: raw traversal shape reached the share: '"
                                + component + "' in " + path);
            }
        }
    }
}

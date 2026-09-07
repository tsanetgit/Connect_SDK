package com.tsanet.receiver.storage.azure;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A strict {@link AzureBlobContainer} double. It enforces the block-blob semantics the
 * adapter's no-partial-visibility claim rests on — a permissive fake here would pass a
 * broken adapter:
 * <ul>
 *   <li>staged blocks are invisible: {@link #sizeOrAbsent} answers {@code -1} for a name
 *       that only ever received uncommitted blocks;</li>
 *   <li>block ids must be valid base64, at most 64 bytes decoded, and the same length as
 *       every other uncommitted id of the same blob (the service's 400 otherwise);</li>
 *   <li>a commit naming a block that was never staged is rejected
 *       ({@code InvalidBlockList}); a commit consumes the blob's uncommitted blocks;</li>
 *   <li>{@code Put Blob} discards any uncommitted blocks of the same name, as the service
 *       does;</li>
 *   <li>a name component that is empty, {@code .}, {@code ..}, or contains a backslash
 *       throws: the live run proved the service normalizes those, so a raw one reaching
 *       the seam means the encoder did not neutralize it.</li>
 * </ul>
 * Failure-injection suppliers let a test make one operation throw a chosen exception;
 * {@link #commitCommitsDespiteFailure} models the ambiguous commit (committed
 * server-side, failed client-side).
 */
final class InMemoryAzureBlobContainer implements AzureBlobContainer {

    private final Map<String, byte[]> committed = new HashMap<>();
    private final Map<String, List<String>> committedBlockIds = new HashMap<>();
    private final Map<String, Map<String, byte[]>> uncommitted = new HashMap<>();

    Supplier<RuntimeException> failPutBlobWith;
    Supplier<RuntimeException> failStageBlockWith;
    Supplier<RuntimeException> failCommitWith;
    Supplier<RuntimeException> failSizeOrAbsentWith;
    Supplier<RuntimeException> failCommittedBlockIdsWith;
    boolean commitCommitsDespiteFailure;

    /** Observed for assertions. */
    int stagedBlocks;
    int commits;
    String lastSizeOrAbsentName;

    @Override
    public void putBlob(String name, String contentType, byte[] bytes) {
        validate(name);
        if (failPutBlobWith != null) {
            throw failPutBlobWith.get();
        }
        committed.put(name, bytes.clone());
        committedBlockIds.put(name, List.of());
        uncommitted.remove(name);
    }

    @Override
    public void stageBlock(String name, String base64BlockId, byte[] buffer, int length) {
        validate(name);
        if (failStageBlockWith != null) {
            throw failStageBlockWith.get();
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64BlockId);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("InvalidBlockId: not base64: " + base64BlockId, e);
        }
        if (decoded.length > 64) {
            throw new IllegalStateException("InvalidBlockId: " + decoded.length + " bytes, cap is 64");
        }
        Map<String, byte[]> blocks = uncommitted.computeIfAbsent(name, k -> new LinkedHashMap<>());
        if (!blocks.isEmpty()) {
            int existing = blocks.keySet().iterator().next().length();
            if (base64BlockId.length() != existing) {
                throw new IllegalStateException(
                        "InvalidBlockId: ids must be the same length within a blob");
            }
        }
        blocks.put(base64BlockId, Arrays.copyOf(buffer, length));
        stagedBlocks++;
    }

    @Override
    public void commitBlockList(String name, List<String> base64BlockIds, String contentType) {
        if (failCommitWith != null) {
            if (commitCommitsDespiteFailure) {
                commit(name, base64BlockIds);
            }
            throw failCommitWith.get();
        }
        commit(name, base64BlockIds);
    }

    private void commit(String name, List<String> base64BlockIds) {
        Map<String, byte[]> blocks = uncommitted.getOrDefault(name, Map.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String id : base64BlockIds) {
            byte[] block = blocks.get(id);
            if (block == null) {
                throw new IllegalStateException("InvalidBlockList: block was never staged: " + id);
            }
            out.writeBytes(block);
        }
        // Visible only now.
        committed.put(name, out.toByteArray());
        committedBlockIds.put(name, List.copyOf(base64BlockIds));
        uncommitted.remove(name);
        commits++;
    }

    @Override
    public List<String> committedBlockIdsOrAbsent(String name) {
        validate(name);
        if (failCommittedBlockIdsWith != null) {
            throw failCommittedBlockIdsWith.get();
        }
        return committedBlockIds.get(name);
    }

    @Override
    public long sizeOrAbsent(String name) {
        validate(name);
        lastSizeOrAbsentName = name;
        if (failSizeOrAbsentWith != null) {
            throw failSizeOrAbsentWith.get();
        }
        byte[] bytes = committed.get(name);
        return bytes == null ? -1 : bytes.length;
    }

    /** For assertions: the committed bytes, or {@code null} if no committed blob exists. */
    byte[] contentOf(String name) {
        byte[] bytes = committed.get(name);
        return bytes == null ? null : bytes.clone();
    }

    /** For assertions: whether staged-but-uncommitted blocks exist for the name. */
    boolean hasUncommittedBlocks(String name) {
        return uncommitted.containsKey(name);
    }

    /** For assertions: every name that currently has uncommitted blocks. */
    List<String> uncommittedNames() {
        return List.copyOf(uncommitted.keySet());
    }

    int committedCount() {
        return committed.size();
    }

    private static void validate(String name) {
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                    || component.contains("\\")) {
                throw new IllegalStateException(
                        "raw traversal shape reached the container: '" + component + "' in " + name);
            }
        }
    }
}

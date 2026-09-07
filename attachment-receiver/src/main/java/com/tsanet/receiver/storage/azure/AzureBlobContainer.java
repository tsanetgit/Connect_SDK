package com.tsanet.receiver.storage.azure;

import java.util.List;

/**
 * The adapter's seam to one Azure Blob Storage container: a 1:1 wrapper over the SDK
 * calls {@link AzureBlobAttachmentStorage} uses, and nothing more. Exists for the same
 * reason {@link AzureShare} does — the SDK's clients are final classes — so offline tests
 * run against a strict in-memory implementation that enforces the block-blob semantics
 * the adapter relies on (staged blocks are invisible until committed; a blob that only
 * ever received uncommitted blocks reads as absent), and {@link SdkAzureBlobContainer}
 * stays logic-free so the live contract run genuinely covers it. Package-private on
 * purpose: this is not a second SPI.
 *
 * <p>Names are already-joined {@code [prefix/]caseNumber/fileName} strings. Blob names
 * are opaque to the service, so nothing is encoded on either side of this seam.
 * Implementations throw their SDK runtime exceptions ({@code BlobStorageException})
 * through; the adapter owns wrapping and classification.
 */
interface AzureBlobContainer {

    /**
     * Small-file path: {@code Put Blob}, one atomic request. Overwrites an existing blob
     * of the same name, matching the other adapters while same-name policy stays
     * implementation-defined upstream (tsanetgit/Connect-API-Code#140, question 2).
     */
    void putBlob(String name, String contentType, byte[] bytes);

    /**
     * Large-file path, step one: {@code Put Block}. Stages the first {@code length} bytes
     * of {@code buffer} as an uncommitted block of the named blob. Nothing becomes visible
     * until {@link #commitBlockList}; a blob that only ever received uncommitted blocks
     * reads as absent and is garbage-collected by the service seven days after the last
     * successful {@code Put Block}. Block ids must be base64 and the same length within
     * one blob, which the adapter guarantees.
     */
    void stageBlock(String name, String base64BlockId, byte[] buffer, int length);

    /**
     * Large-file path, step two: {@code Put Block List}. Commits the named blocks in list
     * order, overwriting any existing blob of the same name; the blob is visible only from
     * this moment.
     */
    void commitBlockList(String name, List<String> base64BlockIds, String contentType);

    /**
     * The committed blob's byte size, or {@code -1} when no committed blob of that name
     * exists ({@code BlobNotFound}). Every other failure throws, a missing container
     * included: an indeterminate check must never read as "absent".
     */
    long sizeOrAbsent(String name);

    /**
     * {@code Get Block List} (committed blocks only): the ids that make up the committed
     * blob, in order; an empty list for a blob written by {@code Put Blob}; {@code null}
     * when no committed blob of that name exists. Every other failure throws.
     */
    List<String> committedBlockIdsOrAbsent(String name);
}

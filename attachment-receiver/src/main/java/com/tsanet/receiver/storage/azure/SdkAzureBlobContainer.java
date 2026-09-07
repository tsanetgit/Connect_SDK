package com.tsanet.receiver.storage.azure;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.Block;
import com.azure.storage.blob.models.BlockListType;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.options.BlockBlobSimpleUploadOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The logic-free SDK implementation of {@link AzureBlobContainer}: every method is one
 * SDK call, so the live contract run is what proves it. The options-bearing overloads are
 * used because they are the ones that carry {@link BlobHttpHeaders} (content type); they
 * send no {@code If-None-Match} condition, which is what makes both {@link #putBlob} and
 * {@link #commitBlockList} overwrite (the plain {@code upload}/{@code commitBlockList}
 * overloads default to create-only).
 */
final class SdkAzureBlobContainer implements AzureBlobContainer {

    private final BlobContainerClient container;

    SdkAzureBlobContainer(BlobContainerClient container) {
        this.container = container;
    }

    @Override
    public void putBlob(String name, String contentType, byte[] bytes) {
        BlockBlobSimpleUploadOptions options =
                new BlockBlobSimpleUploadOptions(new ByteArrayInputStream(bytes), bytes.length);
        if (contentType != null) {
            options.setHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
        blockBlob(name).uploadWithResponse(options, null, Context.NONE);
    }

    @Override
    public void stageBlock(String name, String base64BlockId, byte[] buffer, int length) {
        // Synchronous, and the stream is markable so the SDK's retry policy can replay
        // the block. The adapter reuses the buffer after this returns; keep it that way.
        blockBlob(name).stageBlock(base64BlockId, new ByteArrayInputStream(buffer, 0, length), length);
    }

    @Override
    public void commitBlockList(String name, List<String> base64BlockIds, String contentType) {
        BlockBlobCommitBlockListOptions options = new BlockBlobCommitBlockListOptions(base64BlockIds);
        if (contentType != null) {
            options.setHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
        blockBlob(name).commitBlockListWithResponse(options, null, Context.NONE);
    }

    @Override
    public long sizeOrAbsent(String name) {
        try {
            return container.getBlobClient(name).getProperties().getBlobSize();
        } catch (BlobStorageException e) {
            // Get Blob Properties is a HEAD, so the code arrives in x-ms-error-code. Only a
            // missing blob reads as absent; a missing container or a denied read throws.
            if (BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())) {
                return -1;
            }
            throw e;
        }
    }

    @Override
    public List<String> committedBlockIdsOrAbsent(String name) {
        try {
            List<String> ids = new ArrayList<>();
            for (Block block : blockBlob(name).listBlocks(BlockListType.COMMITTED).getCommittedBlocks()) {
                ids.add(block.getName());
            }
            return ids;
        } catch (BlobStorageException e) {
            if (BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private BlockBlobClient blockBlob(String name) {
        return container.getBlobClient(name).getBlockBlobClient();
    }
}

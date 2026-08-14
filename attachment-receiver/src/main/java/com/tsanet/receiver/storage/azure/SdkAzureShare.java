package com.tsanet.receiver.storage.azure;

import com.azure.storage.file.share.ShareClient;
import com.azure.storage.file.share.ShareDirectoryClient;
import com.azure.storage.file.share.ShareFileClient;
import com.azure.storage.file.share.models.ShareFileUploadRangeOptions;
import com.azure.storage.file.share.models.ShareStorageException;
import com.azure.storage.file.share.options.ShareFileRenameOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * The logic-free SDK implementation of {@link AzureShare}: every method is one SDK
 * call (plus the directory walk), so the live contract run is what proves it. Note the
 * SDK fact the rename path depends on: {@code ShareFileClient.rename} takes its
 * destination relative to the SHARE ROOT, which is why the seam's paths are all
 * share-root-relative in the first place.
 */
final class SdkAzureShare implements AzureShare {

    private final ShareClient share;

    SdkAzureShare(ShareClient share) {
        this.share = share;
    }

    @Override
    public void ensureDirectory(String directoryPath) {
        ShareDirectoryClient dir = share.getRootDirectoryClient();
        for (String component : directoryPath.split("/")) {
            dir = dir.getSubdirectoryClient(component);
            dir.createIfNotExists();
        }
    }

    @Override
    public void createFile(String filePath, long size) {
        share.getFileClient(filePath).create(size);
    }

    @Override
    public void resizeFile(String filePath, long newSize) {
        share.getFileClient(filePath).setProperties(newSize, null, null, null);
    }

    @Override
    public void uploadRange(String filePath, long offset, byte[] bytes) {
        share.getFileClient(filePath).uploadRangeWithResponse(
                new ShareFileUploadRangeOptions(new ByteArrayInputStream(bytes), bytes.length)
                        .setOffset(offset),
                null, null);
    }

    @Override
    public void renameFile(String fromPath, String toPath, boolean replaceIfExists) {
        share.getFileClient(fromPath).renameWithResponse(
                new ShareFileRenameOptions(toPath).setReplaceIfExists(replaceIfExists),
                null, null);
    }

    @Override
    public boolean fileExists(String filePath) {
        try {
            return Boolean.TRUE.equals(share.getFileClient(filePath).exists());
        } catch (ShareStorageException e) {
            if (e.getStatusCode() == 404) {
                // A missing parent directory reads as absent, same as a missing file.
                return false;
            }
            throw e;
        }
    }

    @Override
    public byte[] downloadFile(String filePath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        share.getFileClient(filePath).download(out);
        return out.toByteArray();
    }

    @Override
    public void deleteFile(String filePath) {
        share.getFileClient(filePath).delete();
    }
}

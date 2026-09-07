package com.tsanet.receiver.storage.gcs;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * The logic-free SDK implementation of {@link GcsBucket}: every method is one or two SDK
 * calls, so the live contract run is what proves it. An {@link IOException} from the write
 * channel is re-thrown as {@link StorageException} so the seam presents a single exception
 * family to the adapter, exactly as the raw SDK calls already do.
 */
final class SdkGcsBucket implements GcsBucket {

    private final Storage storage;
    private final String bucket;

    SdkGcsBucket(Storage storage, String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public void create(String key, String contentType, byte[] bytes) {
        storage.create(blobInfo(key, contentType, null), bytes);
    }

    @Override
    public Upload startResumable(String key, String contentType, String attemptMarker) {
        // The BlobInfo opens the resumable session, and its metadata lands on the object
        // when the session finalizes.
        WriteChannel channel = storage.writer(blobInfo(key, contentType, attemptMarker));
        channel.setChunkSize(GcsAttachmentStorage.BUFFER_SIZE);
        return new Upload() {
            @Override
            public void write(byte[] buffer, int length) {
                try {
                    channel.write(ByteBuffer.wrap(buffer, 0, length));
                } catch (IOException e) {
                    throw new StorageException(e);
                }
            }

            @Override
            public void finish() {
                try {
                    channel.close();
                } catch (IOException e) {
                    throw new StorageException(e);
                }
            }

            @Override
            public void abandon() {
                // No finalize, so no object becomes visible; the resumable session expires
                // server-side. GCS exposes no non-finalizing close, so there is nothing to
                // call here — closing the channel would finalize a truncated object.
            }
        };
    }

    @Override
    public long sizeOrAbsent(String key) {
        Blob blob = storage.get(BlobId.of(bucket, key));
        return blob == null ? -1 : blob.getSize();
    }

    @Override
    public String attemptMarkerOrAbsent(String key) {
        Blob blob = storage.get(BlobId.of(bucket, key));
        if (blob == null || blob.getMetadata() == null) {
            return null;
        }
        return blob.getMetadata().get(GcsAttachmentStorage.ATTEMPT_METADATA_KEY);
    }

    @Override
    public byte[] download(String key) {
        return storage.readAllBytes(BlobId.of(bucket, key));
    }

    @Override
    public void delete(String key) {
        storage.delete(BlobId.of(bucket, key));
    }

    private BlobInfo blobInfo(String key, String contentType, String attemptMarker) {
        BlobInfo.Builder builder = BlobInfo.newBuilder(BlobId.of(bucket, key));
        if (contentType != null) {
            builder.setContentType(contentType);
        }
        if (attemptMarker != null) {
            builder.setMetadata(Map.of(GcsAttachmentStorage.ATTEMPT_METADATA_KEY, attemptMarker));
        }
        return builder.build();
    }
}

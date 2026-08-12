package com.tsanet.receiver.storage.s3;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A recording in-memory S3: enough behavior for the adapter's unit tests, with hooks to
 * inject failures per operation. Relies on the SDK v2 interface's default
 * throwing-implementations, so only the operations the adapter uses are implemented.
 */
final class StubS3Client implements S3Client {

    final Map<String, byte[]> objects = new HashMap<>();
    final Map<String, TreeMap<Integer, byte[]>> pendingUploads = new HashMap<>();
    final List<String> calls = new ArrayList<>();

    Supplier<RuntimeException> failPutWith;
    Supplier<RuntimeException> failGetWith;
    Supplier<RuntimeException> failUploadPartWith;
    Supplier<RuntimeException> failCompleteWith;
    Supplier<RuntimeException> failAbortWith;
    Supplier<RuntimeException> failHeadWith;
    boolean completeCommitsDespiteFailure;

    private int uploadCounter;

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        calls.add("put:" + request.key());
        if (failPutWith != null) {
            throw failPutWith.get();
        }
        objects.put(request.key(), toBytes(body));
        return PutObjectResponse.builder().build();
    }

    @Override
    public CreateMultipartUploadResponse createMultipartUpload(CreateMultipartUploadRequest request) {
        String uploadId = "upload-" + (++uploadCounter) + ":" + request.key();
        calls.add("createMultipart:" + request.key());
        pendingUploads.put(uploadId, new TreeMap<>());
        return CreateMultipartUploadResponse.builder().uploadId(uploadId).build();
    }

    @Override
    public UploadPartResponse uploadPart(UploadPartRequest request, RequestBody body) {
        calls.add("uploadPart:" + request.partNumber());
        if (failUploadPartWith != null) {
            throw failUploadPartWith.get();
        }
        pendingUploads.get(request.uploadId()).put(request.partNumber(), toBytes(body));
        return UploadPartResponse.builder().eTag("etag-" + request.partNumber()).build();
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(CompleteMultipartUploadRequest request) {
        calls.add("complete:" + request.key());
        if (failCompleteWith != null) {
            if (completeCommitsDespiteFailure) {
                commit(request.uploadId(), request.key());
            }
            throw failCompleteWith.get();
        }
        commit(request.uploadId(), request.key());
        return CompleteMultipartUploadResponse.builder().build();
    }

    private void commit(String uploadId, String key) {
        TreeMap<Integer, byte[]> parts = pendingUploads.remove(uploadId);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        parts.values().forEach(p -> whole.writeBytes(p));
        objects.put(key, whole.toByteArray());
    }

    @Override
    public AbortMultipartUploadResponse abortMultipartUpload(AbortMultipartUploadRequest request) {
        calls.add("abort:" + request.uploadId());
        if (failAbortWith != null) {
            throw failAbortWith.get();
        }
        pendingUploads.remove(request.uploadId());
        return AbortMultipartUploadResponse.builder().build();
    }

    @Override
    public HeadObjectResponse headObject(HeadObjectRequest request) {
        calls.add("head:" + request.key());
        if (failHeadWith != null) {
            throw failHeadWith.get();
        }
        if (!objects.containsKey(request.key())) {
            throw NoSuchKeyException.builder().statusCode(404).build();
        }
        return HeadObjectResponse.builder().contentLength((long) objects.get(request.key()).length).build();
    }

    @Override
    public <T> T getObject(GetObjectRequest request, ResponseTransformer<GetObjectResponse, T> transformer) {
        calls.add("get:" + request.key());
        byte[] bytes = objects.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().statusCode(404).build();
        }
        try {
            return transformer.transform(GetObjectResponse.builder().contentLength((long) bytes.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        calls.add("get:" + request.key());
        if (failGetWith != null) {
            throw failGetWith.get();
        }
        byte[] bytes = objects.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().statusCode(404).build();
        }
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    @Override
    public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
        calls.add("delete:" + request.key());
        objects.remove(request.key());
        return DeleteObjectResponse.builder().build();
    }

    private static byte[] toBytes(RequestBody body) {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String serviceName() {
        return "s3-stub";
    }

    @Override
    public void close() {
    }
}

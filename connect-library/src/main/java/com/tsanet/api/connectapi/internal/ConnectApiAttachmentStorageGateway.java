package com.tsanet.api.connectapi.internal;

import static com.tsanet.api.connectapi.internal.ConnectApiErrors.call;
import static com.tsanet.api.connectapi.internal.ConnectApiErrors.emptyAnswer;
import static com.tsanet.api.connectapi.internal.ConnectApiErrors.required;

import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.attachments.v2.StorageTestResult;
import com.tsanet.api.facade.AttachmentStorageFacade;
import com.tsanet.api.generated.api.AttachmentStorageConfigApi;
import com.tsanet.api.generated.model.AzureBlobStorageMethodConfigDTO;
import com.tsanet.api.generated.model.S3StorageMethodConfigDTO;
import com.tsanet.api.generated.model.S3StorageMethodConfigRequestDTO;
import com.tsanet.api.generated.model.StorageConfigDTO;
import com.tsanet.api.generated.model.StorageConfigRequestDTO;
import com.tsanet.api.generated.model.StorageConfigTestResultDTO;
import com.tsanet.api.generated.model.StorageConfigVerificationStatus;
import com.tsanet.api.generated.model.StorageMethod;
import java.util.Optional;

/**
 * {@link AttachmentStorageFacade} over the generated {@link AttachmentStorageConfigApi}. Generated
 * DTOs stay inside this class and are mapped field by field through their getters, so renaming a
 * field in the spec breaks this build. Errors map as {@link ConnectApiErrors} maps them; the one
 * status read as an outcome is the {@code 404} on {@link #get()}: no configuration the platform
 * shows here, as {@link AttachmentStorageFacade#get()} says.
 */
public class ConnectApiAttachmentStorageGateway implements AttachmentStorageFacade {

    private final AttachmentStorageConfigApi api;
    private final ConnectApiSessionStore sessionStore;

    public ConnectApiAttachmentStorageGateway(AttachmentStorageConfigApi api, ConnectApiSessionStore sessionStore) {
        this.api = api;
        this.sessionStore = sessionStore;
    }

    @Override
    public Optional<StorageConfig> get() {
        requireLogin();
        StorageConfigDTO dto;
        try {
            dto = call("get storage config", api::getStorageConfig);
        } catch (AttachmentV2Exception e) {
            if (e.is(AttachmentV2Exception.NOT_FOUND)) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.of(toConfig(dto));
    }

    @Override
    public StorageConfig register(StorageTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        requireLogin();
        StorageConfigRequestDTO request = switch (target) {
            case StorageTarget.S3 s3 -> new StorageConfigRequestDTO()
                .method(StorageMethod.S3)
                .s3(new S3StorageMethodConfigRequestDTO()
                    .bucket(s3.bucket())
                    .region(s3.region())
                    .roleArn(s3.roleArn())
                    .prefix(s3.prefix()));
            case StorageTarget.AzureBlob blob -> new StorageConfigRequestDTO()
                .method(StorageMethod.AZURE_BLOB)
                .azureBlob(new AzureBlobStorageMethodConfigDTO()
                    .container(blob.container())
                    .tenantId(blob.tenantId())
                    .storageAccountName(blob.storageAccountName())
                    .prefix(blob.prefix()));
        };
        return toConfig(call("register storage config", () -> api.registerStorageConfig(request)));
    }

    @Override
    public StorageTestResult test() {
        requireLogin();
        StorageConfigTestResultDTO dto = call("test storage config", api::testStorageConfig);
        if (dto == null) {
            throw emptyAnswer("test storage config");
        }
        return new StorageTestResult(
            required(dto.getVerified(), "verified"),
            required(dto.getVerifiedAt(), "verifiedAt"),
            dto.getDetail()
        );
    }

    static StorageConfig toConfig(StorageConfigDTO dto) {
        if (dto == null) {
            throw emptyAnswer("storage config call");
        }
        StorageConfig.Verification verification =
            toVerification(required(dto.getLastVerificationStatus(), "lastVerificationStatus"));
        return switch (required(dto.getMethod(), "method")) {
            case S3 -> {
                S3StorageMethodConfigDTO s3 = required(dto.getS3(), "s3");
                yield new StorageConfig(
                    new StorageTarget.S3(
                        required(s3.getBucket(), "bucket"),
                        required(s3.getRegion(), "region"),
                        required(s3.getRoleArn(), "roleArn"),
                        s3.getPrefix()),
                    required(s3.getExternalId(), "externalId"),
                    verification,
                    dto.getLastVerifiedAt());
            }
            case AZURE_BLOB -> {
                AzureBlobStorageMethodConfigDTO blob = required(dto.getAzureBlob(), "azureBlob");
                yield new StorageConfig(
                    new StorageTarget.AzureBlob(
                        required(blob.getContainer(), "container"),
                        required(blob.getTenantId(), "tenantId"),
                        required(blob.getStorageAccountName(), "storageAccountName"),
                        blob.getPrefix()),
                    null,
                    verification,
                    dto.getLastVerifiedAt());
            }
            // PROVISIONAL(tsanetgit/Connect-API-Code#182): the platform refuses to register GCS, so a
            // GCS configuration can't exist yet. Once GCS is on, it becomes a StorageTarget here.
            case GCS -> throw new AttachmentV2Exception("the registered storage method (gcs) isn't one this client"
                + " reads", 0, AttachmentV2Exception.API_ERROR);
        };
    }

    // Exhaustive switch over the generated enum: a value added to the spec fails this build.
    private static StorageConfig.Verification toVerification(StorageConfigVerificationStatus status) {
        return switch (status) {
            case NEVER_TESTED -> StorageConfig.Verification.NEVER_TESTED;
            case PASSED -> StorageConfig.Verification.PASSED;
            case FAILED -> StorageConfig.Verification.FAILED;
        };
    }

    private void requireLogin() {
        sessionStore.getBearerToken().orElseThrow(() -> new IllegalStateException("Not logged in"));
    }
}

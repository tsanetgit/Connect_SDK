package com.tsanet.api.attachments.v2;

/**
 * Where a receiving company's V2 attachments land: its own storage. No secret is sent or stored:
 * the configuration names the storage, and for S3 the role TSANet assumes. Registering one through
 * {@link com.tsanet.api.facade.AttachmentStorageFacade#register} replaces the company's whole
 * configuration. GCS isn't here: the platform doesn't accept it yet.
 */
public sealed interface StorageTarget permits StorageTarget.S3, StorageTarget.AzureBlob {

    /** The optional key prefix inside the bucket or container; null when none. */
    String prefix();

    /**
     * An AWS S3 bucket that TSANet writes to by assuming {@code roleArn}. The role's trust policy
     * needs the {@code externalId} TSANet generates, which the configuration read back carries
     * ({@link StorageConfig#externalId()}).
     *
     * @param bucket  the bucket's name
     * @param region  the bucket's AWS region, for example {@code us-east-1}
     * @param roleArn the IAM role TSANet assumes to write to the bucket
     * @param prefix  an optional key prefix; null when none
     */
    record S3(String bucket, String region, String roleArn, String prefix) implements StorageTarget {
        public S3 {
            requireValue(bucket, "bucket");
            requireValue(region, "region");
            requireValue(roleArn, "roleArn");
        }
    }

    /**
     * An Azure Blob Storage container.
     *
     * @param container          the container's name
     * @param tenantId           the Microsoft Entra tenant id the platform asks for with the account
     * @param storageAccountName the storage account's name
     * @param prefix             an optional blob name prefix; null when none
     */
    record AzureBlob(String container, String tenantId, String storageAccountName, String prefix)
        implements StorageTarget {
        public AzureBlob {
            requireValue(container, "container");
            requireValue(tenantId, "tenantId");
            requireValue(storageAccountName, "storageAccountName");
        }
    }

    private static void requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

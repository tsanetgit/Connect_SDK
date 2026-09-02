package com.tsanet.receiver.verify;

import com.tsanet.receiver.config.TenantConfig;
import com.tsanet.receiver.storage.AttachmentStorage;
import com.tsanet.receiver.storage.AttachmentStorageException;
import com.tsanet.receiver.storage.azure.AzureFilesAttachmentStorage;
import com.tsanet.receiver.storage.gcs.GcsAttachmentStorage;
import com.tsanet.receiver.storage.s3.S3AttachmentStorage;

import com.azure.storage.file.share.ShareClient;
import com.azure.storage.file.share.ShareClientBuilder;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one production {@link StorageFactory}: turns a {@link TenantConfig}'s
 * {@code storageBackend} string id plus its {@code storageProperties} map into a constructed
 * {@link AttachmentStorage}. This is the "configuration, not code" wiring — a member is
 * onboarded by writing a config row, not by writing an adapter.
 *
 * <p>Construction is lazy: none of the SDK clients built here contact their backend until
 * the first call, so {@code create} succeeding proves only that the config was well formed.
 * {@link GoLiveVerifier} proves reachability by calling {@link AttachmentStorage#verifyAccess()}
 * on what this returns. An unknown backend id throws with the id named.
 *
 * <p><b>The {@code storageProperties} schema this factory reads.</b> Keys are case-sensitive.
 * A required key that is missing or blank is a config error, not a runtime failure. Values
 * are credentials or targets and are never logged here (nor by {@link TenantConfig#toString()},
 * which redacts them).
 *
 * <table>
 *   <caption>Recognised keys by backend</caption>
 *   <tr><th>backend</th><th>required</th><th>optional</th></tr>
 *   <tr><td>{@code s3}</td><td>{@code region}, {@code bucket}</td>
 *       <td>{@code prefix}; {@code accessKeyId}+{@code secretAccessKey} together, else the
 *           AWS default credential chain</td></tr>
 *   <tr><td>{@code azure}</td>
 *       <td>either {@code sasUrl} alone, or {@code connectionString}+{@code shareName}</td>
 *       <td>{@code directoryPrefix}</td></tr>
 *   <tr><td>{@code gcs}</td><td>{@code bucket}</td>
 *       <td>{@code prefix}, {@code projectId}; {@code credentialsJson} (a service-account key),
 *           else Application Default Credentials</td></tr>
 * </table>
 */
public final class RegisteringStorageFactory implements StorageFactory {

    @Override
    public AttachmentStorage create(TenantConfig config) throws AttachmentStorageException {
        Map<String, String> props = config.storageProperties();
        String backend = config.storageBackend();
        return switch (backend) {
            case "s3" -> s3(props);
            case "azure" -> azure(props);
            case "gcs" -> gcs(props);
            default -> throw new AttachmentStorageException(
                    "unknown storage backend '" + backend + "'; known backends are s3, azure, gcs");
        };
    }

    private AttachmentStorage s3(Map<String, String> props) throws AttachmentStorageException {
        String region = require(props, "region", "s3");
        String bucket = require(props, "bucket", "s3");
        String accessKeyId = props.get("accessKeyId");
        String secretAccessKey = props.get("secretAccessKey");
        if (blank(accessKeyId) != blank(secretAccessKey)) {
            throw new AttachmentStorageException(
                    "s3 accessKeyId and secretAccessKey must be provided together, or both omitted "
                            + "to use the AWS default credential chain");
        }
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (!blank(accessKeyId)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
        }
        return new S3AttachmentStorage(builder.build(), bucket, props.get("prefix"));
    }

    private AttachmentStorage azure(Map<String, String> props) throws AttachmentStorageException {
        String sasUrl = props.get("sasUrl");
        ShareClientBuilder builder = new ShareClientBuilder();
        ShareClient share;
        // The Azure SDK echoes a malformed endpoint or connection string back inside its
        // own exception chain (MalformedURLException: no protocol: <the whole value>), so a
        // connection string pasted into the sasUrl key would put an account key into any
        // logged config error. Both branches therefore throw a value-free message and,
        // unlike the gcs branch, deliberately attach NO cause: here the cause is the leak.
        if (!blank(sasUrl)) {
            try {
                share = builder.endpoint(sasUrl).buildClient();
            } catch (RuntimeException e) {
                throw new AttachmentStorageException(
                        "azure sasUrl is not a well-formed share SAS URL; it must be an https URL "
                                + "that includes the share name");
            }
        } else {
            String connectionString = require(props, "connectionString", "azure");
            String shareName = require(props, "shareName", "azure");
            try {
                share = builder.connectionString(connectionString).shareName(shareName).buildClient();
            } catch (RuntimeException e) {
                throw new AttachmentStorageException(
                        "azure connectionString is not a well-formed storage connection string");
            }
        }
        return AzureFilesAttachmentStorage.forShare(share, props.get("directoryPrefix"));
    }

    private AttachmentStorage gcs(Map<String, String> props) throws AttachmentStorageException {
        String bucket = require(props, "bucket", "gcs");
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        String projectId = props.get("projectId");
        if (!blank(projectId)) {
            builder.setProjectId(projectId);
        }
        String credentialsJson = props.get("credentialsJson");
        if (!blank(credentialsJson)) {
            try {
                builder.setCredentials(GoogleCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))));
            } catch (IOException e) {
                // Deliberately not echoing the parser's message into this thrown message:
                // credentialsJson is key material, and the config error must carry none of it.
                // The cause chain keeps the library's own (value-free) detail for debugging.
                throw new AttachmentStorageException(
                        "gcs credentialsJson is not a readable service-account key", e);
            }
        }
        Storage storage = builder.build().getService();
        return GcsAttachmentStorage.forBucket(storage, bucket, props.get("prefix"));
    }

    private static String require(Map<String, String> props, String key, String backend)
            throws AttachmentStorageException {
        String value = props.get(key);
        if (blank(value)) {
            throw new AttachmentStorageException(
                    backend + " storage requires a non-blank '" + key + "' property");
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

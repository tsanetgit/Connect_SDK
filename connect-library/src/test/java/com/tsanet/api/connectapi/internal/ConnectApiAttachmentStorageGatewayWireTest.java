package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.attachments.v2.StorageTestResult;
import com.tsanet.api.generated.api.AttachmentStorageConfigApi;
import com.tsanet.api.generated.invoker.ApiClient;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The receiver storage calls through the generated {@link AttachmentStorageConfigApi}, the real
 * {@link ApiClient} and the library's own RestTemplate, against a mock server: the paths and JSON
 * on the wire are the spec's, the bearer rides along, {@code externalId} is read but never sent,
 * a {@code 404} on read is "none shown" (see {@code AttachmentStorageFacade.get()}), and a failed
 * test is a result.
 */
class ConnectApiAttachmentStorageGatewayWireTest {

    private static final String BASE = "https://connect.example";
    private static final String CONFIG = BASE + "/v2/attachments/storage-config";

    private MockRestServiceServer server;
    private ConnectApiAttachmentStorageGateway gateway;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-123");
        gateway = new ConnectApiAttachmentStorageGateway(new AttachmentStorageConfigApi(apiClient),
            GatewayTestSupport.authenticatedSessionStore());
    }

    private static final String S3_CONFIG = "{\"method\":\"s3\",\"s3\":{\"bucket\":\"acme-attachments\","
        + "\"prefix\":\"tsanet/\",\"region\":\"us-east-1\",\"roleArn\":\"arn:aws:iam::123456789012:role/tsanet-writer\","
        + "\"externalId\":\"ext-abc\"},\"lastVerificationStatus\":\"passed\","
        + "\"lastVerifiedAt\":\"2026-10-03T12:00:00Z\",\"futureField\":true}";

    private static final String AZURE_CONFIG = "{\"method\":\"azureBlob\",\"azureBlob\":{\"container\":\"inbound\","
        + "\"tenantId\":\"00000000-0000-0000-0000-000000000001\",\"storageAccountName\":\"acmestore\"},"
        + "\"lastVerificationStatus\":\"never_tested\"}";

    @Test
    void getReadsAnS3ConfigurationWithItsExternalIdAndVerification() {
        server.expect(requestTo(CONFIG)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer bearer-123"))
            .andRespond(withSuccess(S3_CONFIG, MediaType.APPLICATION_JSON));

        StorageConfig config = gateway.get().orElseThrow();

        assertThat(config.target()).isEqualTo(new StorageTarget.S3("acme-attachments", "us-east-1",
            "arn:aws:iam::123456789012:role/tsanet-writer", "tsanet/"));
        assertThat(config.externalId()).isEqualTo("ext-abc");
        assertThat(config.verification()).isEqualTo(StorageConfig.Verification.PASSED);
        assertThat(config.lastVerifiedAt()).isEqualTo(OffsetDateTime.parse("2026-10-03T12:00:00Z"));
        server.verify();
    }

    @Test
    void getReadsAnAzureBlobConfigurationWithNoExternalId() {
        server.expect(requestTo(CONFIG)).andRespond(withSuccess(AZURE_CONFIG, MediaType.APPLICATION_JSON));

        StorageConfig config = gateway.get().orElseThrow();

        assertThat(config.target()).isEqualTo(new StorageTarget.AzureBlob("inbound",
            "00000000-0000-0000-0000-000000000001", "acmestore", null));
        assertThat(config.externalId()).isNull();
        assertThat(config.verification()).isEqualTo(StorageConfig.Verification.NEVER_TESTED);
        assertThat(config.lastVerifiedAt()).isNull();
    }

    @Test
    void getIsEmptyWhenNothingIsRegistered() {
        server.expect(requestTo(CONFIG)).andRespond(problem(HttpStatus.NOT_FOUND));

        assertThat(gateway.get()).isEmpty();
    }

    @Test
    void getStillFailsOnAnyOtherError() {
        server.expect(requestTo(CONFIG)).andRespond(problem(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> gateway.get())
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.API_ERROR));
    }

    @Test
    void registerSendsAnS3TargetWithoutAnExternalIdAndReadsTheOneTsanetGenerated() {
        server.expect(requestTo(CONFIG)).andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer bearer-123"))
            // The generated client writes unset optional fields as null; externalId is never written.
            .andExpect(content().json("{\"method\":\"s3\",\"s3\":{\"bucket\":\"acme-attachments\",\"prefix\":\"tsanet/\","
                + "\"region\":\"us-east-1\",\"roleArn\":\"arn:aws:iam::123456789012:role/tsanet-writer\"},"
                + "\"azureBlob\":null,\"gcs\":null}", true))
            .andRespond(withSuccess(S3_CONFIG, MediaType.APPLICATION_JSON));

        StorageConfig config = gateway.register(new StorageTarget.S3("acme-attachments", "us-east-1",
            "arn:aws:iam::123456789012:role/tsanet-writer", "tsanet/"));

        assertThat(config.externalId()).isEqualTo("ext-abc");
        server.verify();
    }

    @Test
    void registerSendsAnAzureBlobTarget() {
        server.expect(requestTo(CONFIG)).andExpect(method(HttpMethod.PUT))
            .andExpect(content().json("{\"method\":\"azureBlob\",\"azureBlob\":{\"container\":\"inbound\",\"prefix\":null,"
                + "\"tenantId\":\"00000000-0000-0000-0000-000000000001\",\"storageAccountName\":\"acmestore\"},"
                + "\"s3\":null,\"gcs\":null}", true))
            .andRespond(withSuccess(AZURE_CONFIG, MediaType.APPLICATION_JSON));

        StorageConfig config = gateway.register(new StorageTarget.AzureBlob("inbound",
            "00000000-0000-0000-0000-000000000001", "acmestore", null));

        assertThat(config.target()).isInstanceOf(StorageTarget.AzureBlob.class);
        server.verify();
    }

    @Test
    void aConfigurationThePlatformWontRegisterIsInvalidRequest() {
        server.expect(requestTo(CONFIG)).andExpect(method(HttpMethod.PUT)).andRespond(problem(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> gateway.register(new StorageTarget.AzureBlob("inbound", "tenant", "acmestore", null)))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.INVALID_REQUEST);
                assertThat(ex.status()).isEqualTo(400);
                assertThat(ex.getMessage()).contains("register storage config");
                assertThat(ex.getCause()).isInstanceOf(ConnectApiException.class);
            });
    }

    @Test
    void aFailedTestIsAResultNotAnException() {
        server.expect(requestTo(CONFIG + "/test")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"verified\":false,\"verifiedAt\":\"2026-10-03T12:05:00Z\","
                + "\"detail\":\"AccessDenied assuming the role\"}", MediaType.APPLICATION_JSON));

        StorageTestResult result = gateway.test();

        assertThat(result.verified()).isFalse();
        assertThat(result.verifiedAt()).isEqualTo(OffsetDateTime.parse("2026-10-03T12:05:00Z"));
        assertThat(result.detail()).isEqualTo("AccessDenied assuming the role");
        server.verify();
    }

    @Test
    void aPassedTestIsVerified() {
        server.expect(requestTo(CONFIG + "/test"))
            .andRespond(withSuccess("{\"verified\":true,\"verifiedAt\":\"2026-10-03T12:05:00Z\"}",
                MediaType.APPLICATION_JSON));

        assertThat(gateway.test().verified()).isTrue();
    }

    @Test
    void testingWithNothingRegisteredIsNotFound() {
        server.expect(requestTo(CONFIG + "/test")).andRespond(problem(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gateway.test())
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.NOT_FOUND));
    }

    @Test
    void anS3ConfigurationWithoutItsExternalIdIsAPrecondition() {
        server.expect(requestTo(CONFIG)).andRespond(withSuccess(S3_CONFIG.replace(",\"externalId\":\"ext-abc\"", ""),
            MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.get())
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.CLIENT_PRECONDITION);
                assertThat(ex.getMessage()).contains("externalId");
            });
    }

    // PROVISIONAL(tsanetgit/Connect-API-Code#182): pins the gateway's refusal of a GCS configuration,
    // which can't exist while the platform refuses to register GCS. Once GCS is on, this reads one.
    @Test
    void aGcsConfigurationIsOneThisClientDoesntRead() {
        server.expect(requestTo(CONFIG)).andRespond(withSuccess("{\"method\":\"gcs\",\"gcs\":{},"
            + "\"lastVerificationStatus\":\"never_tested\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.get())
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.API_ERROR));
    }

    @Test
    void nothingIsSentWithoutALogin() {
        ConnectApiAttachmentStorageGateway loggedOut = new ConnectApiAttachmentStorageGateway(
            new AttachmentStorageConfigApi(new ApiClient(ConnectApiRestTemplates.create())), new ConnectApiSessionStore());

        assertThatThrownBy(loggedOut::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(loggedOut::test).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> loggedOut.register(new StorageTarget.AzureBlob("c", "t", "a", null)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aTargetMissingARequiredFieldIsRefusedBeforeAnyRequest() {
        Map<String, Runnable> blanks = Map.of(
            "bucket", () -> new StorageTarget.S3(" ", "us-east-1", "arn", null),
            "region", () -> new StorageTarget.S3("b", null, "arn", null),
            "roleArn", () -> new StorageTarget.S3("b", "us-east-1", "", null),
            "container", () -> new StorageTarget.AzureBlob(null, "t", "a", null),
            "tenantId", () -> new StorageTarget.AzureBlob("c", " ", "a", null),
            "storageAccountName", () -> new StorageTarget.AzureBlob("c", "t", null, null));
        blanks.forEach((field, build) -> assertThatThrownBy(build::run).as(field)
            .isInstanceOf(IllegalArgumentException.class).hasMessage(field + " must not be blank"));
        assertThatThrownBy(() -> gateway.register(null)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    private static org.springframework.test.web.client.ResponseCreator problem(HttpStatus status) {
        return withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body("{\"title\":\"Title " + status.value() + "\",\"status\":" + status.value() + ",\"detail\":\"why\"}");
    }
}

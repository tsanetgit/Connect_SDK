package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tsanet.api.attachments.v2.AttachmentCompleteRequest;
import com.tsanet.api.attachments.v2.AttachmentCompleteResult;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantRequest;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.generated.invoker.ApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The V2 calls through the real {@link ApiClient} and its converters, against a mock server:
 * the JSON on the wire is the contract's (tsanetgit/Connect-API-Code#147) field for field,
 * the bearer and idempotency headers ride along, and problem-details answers come back as
 * value-free {@link AttachmentV2Exception}s.
 */
class ConnectApiAttachmentsV2ApiTest {

    private static final String BASE = "https://connect.example";
    private static final String TOKEN = "case-token-1";
    private static final UUID GRANT_ID = UUID.fromString("3f2b7e6a-2c1d-4a4e-9b8f-0c1d2e3f4a5b");

    private MockRestServiceServer server;
    private ConnectApiAttachmentsV2Api api;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-123");
        api = new ConnectApiAttachmentsV2Api(apiClient);
    }

    @Test
    void grantSendsTheContractBodyWithBearerAndIdempotencyKeyAndReadsTheGrant() {
        String expectedBody = "{\"fileName\":\"diag.log\",\"contentType\":\"text/plain\",\"sizeBytes\":12,"
            + "\"sha256\":\"ab\",\"description\":\"logs\"}";
        String grantJson = "{\"grantId\":\"" + GRANT_ID + "\",\"fileName\":\"diag.log\","
            + "\"expiresAt\":\"2026-09-08T10:00:00Z\","
            + "\"receiver\":{\"companyId\":7,\"targetKind\":\"s3\",\"maxSizeBytes\":5000000000},"
            + "\"upload\":{\"mode\":\"multipart\",\"method\":\"PUT\","
            + "\"headers\":{\"Content-Type\":\"application/octet-stream\"},"
            + "\"parts\":[{\"partNumber\":1,\"url\":\"https://store.example/p1?sig=SECRET\",\"sizeBytes\":8},"
            + "{\"partNumber\":2,\"url\":\"https://store.example/p2?sig=SECRET\",\"sizeBytes\":4}]},"
            + "\"verification\":{\"mode\":\"platform\"},\"futureField\":true}";
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer bearer-123"))
            .andExpect(header("Idempotency-Key", "idem-1"))
            .andExpect(content().json(expectedBody, true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(grantJson));

        AttachmentGrant grant = api.createGrant(TOKEN,
            new AttachmentGrantRequest("diag.log", "text/plain", 12, "ab", "logs"), "idem-1");

        assertThat(grant.grantId()).isEqualTo(GRANT_ID);
        assertThat(grant.receiver().targetKind()).isEqualTo("s3");
        assertThat(grant.upload().mode()).isEqualTo("multipart");
        assertThat(grant.upload().headers()).containsEntry("Content-Type", "application/octet-stream");
        assertThat(grant.upload().parts()).hasSize(2);
        assertThat(grant.upload().parts().get(1).sizeBytes()).isEqualTo(4);
        assertThat(grant.verification().mode()).isEqualTo("platform");
        assertThat(grant.toString()).doesNotContain("SECRET").doesNotContain("store.example");
        server.verify();
    }

    @Test
    void grantOmitsAbsentOptionalFields() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants"))
            .andExpect(content().json("{\"fileName\":\"a.bin\",\"contentType\":\"application/octet-stream\",\"sizeBytes\":1}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"grantId\":\"" + GRANT_ID + "\",\"fileName\":\"a.bin\",\"expiresAt\":\"2026-09-08T10:00:00Z\","
                    + "\"upload\":{\"mode\":\"single\",\"url\":\"https://store.example/a\"},\"verification\":{\"mode\":\"none\"}}"));

        AttachmentGrant grant = api.createGrant(TOKEN,
            new AttachmentGrantRequest("a.bin", "application/octet-stream", 1, null, null), null);

        assertThat(grant.upload().parts()).isEmpty();
        assertThat(grant.upload().headers()).isEmpty();
        server.verify();
    }

    @Test
    void completeSendsReceiptsAndReadsTheRecordedOutcome() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants/" + GRANT_ID + "/complete"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"sizeBytes\":12,\"parts\":[{\"partNumber\":1,\"receipt\":\"\\\"etag-1\\\"\"},"
                + "{\"partNumber\":2,\"receipt\":\"\\\"etag-2\\\"\"}]}", true))
            .andRespond(withSuccess("{\"grantId\":\"" + GRANT_ID + "\",\"fileName\":\"diag.log\",\"status\":\"DELIVERED\","
                + "\"verification\":{\"method\":\"HEAD\",\"verifiedAt\":\"2026-09-08T10:01:00Z\",\"sizeMatched\":true},"
                + "\"noteId\":501,\"message\":\"stored\"}", MediaType.APPLICATION_JSON));

        AttachmentCompleteResult result = api.complete(TOKEN, GRANT_ID, new AttachmentCompleteRequest(12, null,
            List.of(new AttachmentCompleteRequest.PartReceipt(1, "\"etag-1\""),
                new AttachmentCompleteRequest.PartReceipt(2, "\"etag-2\""))));

        assertThat(result.delivered()).isTrue();
        assertThat(result.verification().sizeMatched()).isTrue();
        assertThat(result.noteId()).isEqualTo(501L);
        server.verify();
    }

    @Test
    void singleModeCompleteCarriesOnlyTheSize() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants/" + GRANT_ID + "/complete"))
            .andExpect(content().json("{\"sizeBytes\":3}", true))
            .andRespond(withSuccess("{\"grantId\":\"" + GRANT_ID + "\",\"fileName\":\"a\",\"status\":\"DELIVERED_UNVERIFIED\"}",
                MediaType.APPLICATION_JSON));

        AttachmentCompleteResult result = api.complete(TOKEN, GRANT_ID, new AttachmentCompleteRequest(3, null, List.of()));

        assertThat(result.deliveredUnverified()).isTrue();
        assertThat(result.delivered()).isFalse();
        server.verify();
    }

    @Test
    void abandonIsADeleteOnTheGrant() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants/" + GRANT_ID))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        api.abandon(TOKEN, GRANT_ID);

        server.verify();
    }

    @Test
    void problemDetailsBecomeATypedValueFreeException() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants/" + GRANT_ID + "/complete"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"type\":\"attachment/grant-expired\",\"title\":\"Grant expired\",\"status\":409,"
                    + "\"detail\":\"The grant expired before completion\"}"));

        assertThatThrownBy(() -> api.complete(TOKEN, GRANT_ID, new AttachmentCompleteRequest(3, null, List.of())))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.status()).isEqualTo(409);
                assertThat(ex.problemType()).isEqualTo("attachment/grant-expired");
                assertThat(ex.isProblem(AttachmentV2Exception.GRANT_EXPIRED)).isTrue();
                assertThat(ex.getMessage()).contains("HTTP 409").contains("Grant expired").contains("before completion");
            });
    }

    @Test
    void aNonProblemErrorBodyYieldsTheStatusAlone() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>gateway sig=SECRET</html>"));

        assertThatThrownBy(() -> api.createGrant(TOKEN,
            new AttachmentGrantRequest("a", "text/plain", 1, null, null), "k"))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.status()).isEqualTo(502);
                assertThat(ex.problemType()).isNull();
                assertThat(ex.getMessage()).contains("HTTP 502").doesNotContain("SECRET");
            });
    }

    @Test
    void aGrantWithoutAnUploadBlockIsRejectedClientSide() {
        server.expect(requestTo(BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"grantId\":\"" + GRANT_ID + "\",\"fileName\":\"a\"}"));

        assertThatThrownBy(() -> api.createGrant(TOKEN, new AttachmentGrantRequest("a", "text/plain", 1, null, null), null))
            .isInstanceOf(AttachmentV2Exception.class)
            .hasMessageContaining("upload block");
    }
}

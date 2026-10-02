package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.attachments.v2.AttachmentGrant;
import com.tsanet.api.attachments.v2.AttachmentGrantPage;
import com.tsanet.api.attachments.v2.AttachmentV2Exception;
import com.tsanet.api.attachments.v2.UploadLink;
import com.tsanet.api.attachments.v2.UploadReceipts;
import com.tsanet.api.generated.api.AttachmentGrantsApi;
import com.tsanet.api.generated.invoker.ApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The V2 calls through the generated {@link AttachmentGrantsApi}, the real {@link ApiClient}
 * and the library's own RestTemplate, against a mock server: the paths and JSON on the wire
 * are the spec's field for field, the bearer rides along, links are mapped without their
 * secrets reaching {@code toString}, and every documented error status comes back as its own
 * {@link AttachmentV2Exception} code.
 */
class ConnectApiAttachmentsV2GatewayWireTest {

    private static final String BASE = "https://connect.example";
    private static final String TOKEN = "case-token-1";
    private static final long GRANT_ID = 9001L;
    private static final String GRANTS = BASE + "/v2/collaboration-requests/" + TOKEN + "/attachments/grants";
    private static final String GRANT = GRANTS + "/" + GRANT_ID;

    private MockRestServiceServer server;
    private ConnectApiAttachmentsV2Gateway gateway;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-123");
        gateway = new ConnectApiAttachmentsV2Gateway(new AttachmentGrantsApi(apiClient),
            GatewayTestSupport.authenticatedSessionStore());
    }

    private static String grantJson(String mode, String status, String plan) {
        return "{\"grantId\":" + GRANT_ID + ",\"status\":\"" + status + "\",\"fileName\":\"diag.log\","
            + "\"expectedSizeBytes\":12,\"createdAt\":\"2026-10-01T12:00:00Z\","
            + "\"expiresAt\":\"2026-10-01T13:00:00Z\",\"mode\":\"" + mode + "\"" + plan + ",\"futureField\":true}";
    }

    @Test
    void createGrantSendsOnlyTheNameAndSizeWithTheBearerAndMapsThePlan() {
        server.expect(requestTo(GRANTS))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer bearer-123"))
            .andExpect(content().json("{\"fileName\":\"diag.log\",\"expectedSizeBytes\":12}", true))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("s3Multipart", "open", ",\"s3Multipart\":{\"totalParts\":2,\"partSizeBytes\":8}")));

        AttachmentGrant grant = gateway.createGrant(TOKEN, "diag.log", 12);

        assertThat(grant.grantId()).isEqualTo(GRANT_ID);
        assertThat(grant.status()).isEqualTo(AttachmentGrant.Status.OPEN);
        assertThat(grant.mode()).isEqualTo(AttachmentGrant.UploadMode.S3_MULTIPART);
        assertThat(grant.plan()).isEqualTo(new AttachmentGrant.UploadPlan(2, 8));
        assertThat(grant.expectedSizeBytes()).isEqualTo(12);
        server.verify();
    }

    @Test
    void anAzureGrantMapsItsBlockPlanAndASingleGrantHasNone() {
        server.expect(requestTo(GRANT)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("azureBlock", "completed", ",\"azureBlock\":{\"totalBlocks\":3,\"blockSizeBytes\":4}")));
        server.expect(requestTo(GRANT)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("single", "abandoned", "")));

        AttachmentGrant azure = gateway.getGrant(TOKEN, GRANT_ID);
        AttachmentGrant single = gateway.getGrant(TOKEN, GRANT_ID);

        assertThat(azure.plan()).isEqualTo(new AttachmentGrant.UploadPlan(3, 4));
        assertThat(azure.completed()).isTrue();
        assertThat(single.plan()).isNull();
        assertThat(single.status()).isEqualTo(AttachmentGrant.Status.ABANDONED);
        server.verify();
    }

    @Test
    void s3PartLinksPostsTheNumbersAndMapsEveryLinkWithoutLeakingIt() {
        server.expect(requestTo(GRANT + "/s3-multipart/parts"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"partNumbers\":[1,2]}", true))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"parts\":["
                + "{\"partNumber\":1,\"url\":\"https://store.example/p1?sig=SENTINEL\",\"sizeBytes\":8,"
                + "\"expiresAt\":\"2026-10-01T12:30:00Z\",\"headers\":{\"x-amz-meta\":\"SENTINEL-HDR\"}},"
                + "{\"partNumber\":2,\"url\":\"https://store.example/p2?sig=SENTINEL\",\"sizeBytes\":4,"
                + "\"expiresAt\":\"2026-10-01T12:30:00Z\"}]}"));

        List<UploadLink> links = gateway.s3PartLinks(TOKEN, GRANT_ID, List.of(1, 2));

        assertThat(links).extracting(UploadLink::number, UploadLink::sizeBytes)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 8L), org.assertj.core.groups.Tuple.tuple(2, 4L));
        assertThat(links.get(0).headers()).isEqualTo(Map.of("x-amz-meta", "SENTINEL-HDR"));
        assertThat(links.get(1).headers()).isEmpty();
        assertThat(links.toString()).doesNotContain("SENTINEL").doesNotContain("store.example");
        server.verify();
    }

    @Test
    void azureBlockLinksPostTheBlockNumbersAndASingleLinkTakesNoBody() {
        server.expect(requestTo(GRANT + "/azure-block/blocks"))
            .andExpect(content().json("{\"blockNumbers\":[3]}", true))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"blocks\":["
                + "{\"blockNumber\":3,\"url\":\"https://blob.example/b3\",\"sizeBytes\":4,"
                + "\"expiresAt\":\"2026-10-01T12:30:00Z\",\"headers\":{\"x-ms-version\":\"2021-08-06\"}}]}"));
        server.expect(requestTo(GRANT + "/single/url"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body("{\"url\":\"https://store.example/one\",\"expiresAt\":\"2026-10-01T12:30:00Z\"}"));

        UploadLink block = gateway.azureBlockLinks(TOKEN, GRANT_ID, List.of(3)).get(0);
        UploadLink single = gateway.singleUploadLink(TOKEN, GRANT_ID);

        assertThat(block.number()).isEqualTo(3);
        assertThat(block.headers()).containsEntry("x-ms-version", "2021-08-06");
        assertThat(single.number()).isEqualTo(1);
        assertThat(single.sizeBytes()).isNull();
        server.verify();
    }

    @Test
    void completeS3MultipartSendsEveryReceiptWithItsEtagExactly() {
        server.expect(requestTo(GRANT + "/s3-multipart/complete"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"parts\":[{\"partNumber\":1,\"etag\":\"\\\"e1\\\"\"},"
                + "{\"partNumber\":2,\"etag\":\"\\\"e2\\\"\"}]}", true))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("s3Multipart", "completed", ",\"s3Multipart\":{\"totalParts\":2,\"partSizeBytes\":8}")));

        AttachmentGrant done = gateway.completeS3Multipart(TOKEN, GRANT_ID, List.of(
            new UploadReceipts.PartReceipt(1, "\"e1\""), new UploadReceipts.PartReceipt(2, "\"e2\"")));

        assertThat(done.completed()).isTrue();
        server.verify();
    }

    @Test
    void singleAndAzureCompleteAndAbandonArePostsOnTheGrant() {
        server.expect(requestTo(GRANT + "/single/complete")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(grantJson("single", "completed", "")));
        server.expect(requestTo(GRANT + "/azure-block/complete")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("azureBlock", "completed", ",\"azureBlock\":{\"totalBlocks\":3,\"blockSizeBytes\":4}")));
        server.expect(requestTo(GRANT + "/abandon")).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(grantJson("single", "abandoned", "")));

        gateway.completeSingle(TOKEN, GRANT_ID);
        gateway.completeAzureBlock(TOKEN, GRANT_ID);
        assertThat(gateway.abandon(TOKEN, GRANT_ID).status()).isEqualTo(AttachmentGrant.Status.ABANDONED);
        server.verify();
    }

    @Test
    void listGrantsPassesThePageAndSize() {
        server.expect(requestTo(GRANTS + "?page=2&size=5")).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"content\":["
                + grantJson("single", "open", "") + "],\"totalElements\":11,\"totalPages\":3,\"size\":5,\"number\":2}"));

        AttachmentGrantPage page = gateway.listGrants(TOKEN, 2, 5);

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(11);
        assertThat(page.number()).isEqualTo(2);
        server.verify();
    }

    @Test
    void everyDocumentedErrorStatusHasItsOwnCode() {
        Map<Integer, String> expected = Map.of(
            400, AttachmentV2Exception.INVALID_REQUEST,
            403, AttachmentV2Exception.FORBIDDEN,
            404, AttachmentV2Exception.NOT_FOUND,
            409, AttachmentV2Exception.GRANT_TERMINAL,
            422, AttachmentV2Exception.UPLOAD_MISMATCH,
            502, AttachmentV2Exception.PROVIDER_ERROR,
            500, AttachmentV2Exception.API_ERROR);
        expected.forEach((status, code) -> {
            server.reset();
            server.expect(requestTo(GRANT + "/single/complete"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"title\":\"Title " + status + "\",\"status\":" + status + ",\"detail\":\"why\"}"));

            assertThatThrownBy(() -> gateway.completeSingle(TOKEN, GRANT_ID))
                .isInstanceOf(AttachmentV2Exception.class)
                .satisfies(e -> {
                    AttachmentV2Exception ex = (AttachmentV2Exception) e;
                    assertThat(ex.code()).as("status %d", status).isEqualTo(code);
                    assertThat(ex.status()).isEqualTo(status);
                    assertThat(ex.getMessage()).contains("complete single upload").contains("Title " + status);
                    assertThat(ex.getCause()).isInstanceOf(ConnectApiException.class);
                });
        });
    }

    private static String forbidden(String type) {
        return "{" + (type == null ? "" : "\"type\":\"https://api.tsanet.org/errors/" + type + "\",")
            + "\"title\":\"Forbidden\",\"status\":403,\"detail\":\"why\"}";
    }

    @Test
    void theServersAllowlistRefusalOnCreateIsReceiverNotAllowed() {
        server.expect(requestTo(GRANTS)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(forbidden("attachment-receiver-not-allowed")));

        assertThatThrownBy(() -> gateway.createGrant(TOKEN, "diag.log", 12))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.RECEIVER_NOT_ALLOWED);
                assertThat(ex.status()).isEqualTo(403);
                assertThat(ex.getCause()).isInstanceOf(ConnectApiException.class);
            });
    }

    @Test
    void anyOtherForbiddenOnCreateStaysForbidden() {
        for (String type : new String[] {"access-denied", null}) {
            server.reset();
            server.expect(requestTo(GRANTS)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(forbidden(type)));

            assertThatThrownBy(() -> gateway.createGrant(TOKEN, "diag.log", 12))
                .isInstanceOf(AttachmentV2Exception.class)
                .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).as("type %s", type)
                    .isEqualTo(AttachmentV2Exception.FORBIDDEN));
        }
    }

    private static final String CASE = BASE + "/v1/collaboration-requests/" + TOKEN + "?includeRemovedNotes=false";

    /** The gateway as the runtime builds it with an allowlist: the real case lookup, on the same client. */
    private ConnectApiAttachmentsV2Gateway allowlisted(long allowed) {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-123");
        return new ConnectApiAttachmentsV2Gateway(new AttachmentGrantsApi(apiClient),
            GatewayTestSupport.authenticatedSessionStore(), java.util.Set.of(allowed),
            ConnectApiAttachmentsV2Gateway.receivingCompanyFrom(new com.tsanet.api.generated.api.CollaborationRequestsApi(apiClient)));
    }

    @Test
    void theRealCaseLookupRefusesAReceiverOffTheListWithNoGrantRequest() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(1112L);
        server.expect(requestTo(CASE)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":3642,\"token\":\"" + TOKEN + "\",\"receiveCompanyId\":1113,\"submitCompanyId\":1112}"));

        assertThatThrownBy(() -> guarded.createGrant(TOKEN, "diag.log", 12))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.RECEIVER_NOT_ALLOWED));
        server.verify();
    }

    @Test
    void aFailedCaseLookupKeepsItsCodeAndDoesNotQuoteTheToken() {
        ConnectApiAttachmentsV2Gateway guarded = allowlisted(1112L);
        server.expect(requestTo(CASE)).andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\":\"Not Found\",\"status\":404,\"detail\":\"no case at /v1/collaboration-requests/"
                    + TOKEN + "\",\"instance\":\"/v1/collaboration-requests/" + TOKEN + "\"}"));

        assertThatThrownBy(() -> guarded.createGrant(TOKEN, "diag.log", 12))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.NOT_FOUND);
                assertThat(e.getMessage()).doesNotContain(TOKEN);
            });
        server.verify();
    }

    @Test
    void theAllowlistTypeOnAnyOtherCallStaysForbidden() {
        server.expect(requestTo(GRANT + "/single/complete"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(forbidden("attachment-receiver-not-allowed")));

        assertThatThrownBy(() -> gateway.completeSingle(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.FORBIDDEN));
    }

    @Test
    void anErrorBodyThatEchoesTheRequestPathDoesNotLeakTheCaseToken() {
        server.expect(requestTo(GRANT + "/abandon"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\":\"Conflict\",\"status\":409,\"detail\":\"grant at /v2/collaboration-requests/"
                    + TOKEN + "/attachments/grants/" + GRANT_ID + "/abandon is terminal\"}"));

        assertThatThrownBy(() -> gateway.abandon(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                assertThat(((AttachmentV2Exception) e).code()).isEqualTo(AttachmentV2Exception.GRANT_TERMINAL);
                assertThat(e.getMessage()).contains("is terminal").doesNotContain(TOKEN);
            });
    }

    @Test
    void anHttpErrorOnAPlainRestTemplateKeepsItsStatusCodeAndDropsTheBody() {
        // Without the library's error handler, Spring answers with its own status exception,
        // whose body is unscrubbed.
        RestTemplate plain = new RestTemplate();
        MockRestServiceServer plainServer = MockRestServiceServer.bindTo(plain).build();
        ApiClient plainClient = new ApiClient(plain);
        plainClient.setBasePath(BASE);
        plainClient.setBearerToken(() -> "bearer-123");
        ConnectApiAttachmentsV2Gateway plainGateway = new ConnectApiAttachmentsV2Gateway(
            new AttachmentGrantsApi(plainClient), GatewayTestSupport.authenticatedSessionStore());
        plainServer.expect(requestTo(GRANT + "/abandon"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"/v2/collaboration-requests/" + TOKEN + " is terminal\"}"));

        assertThatThrownBy(() -> plainGateway.abandon(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.GRANT_TERMINAL);
                assertThat(ex.status()).isEqualTo(409);
                assertThat(ex.getMessage()).doesNotContain(TOKEN);
            });
    }

    @Test
    void anAnswerTheClientCannotReadIsAnApiErrorNotConnectivity() {
        server.expect(requestTo(GRANT + "/single/complete"))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .body(grantJson("ociMultipart", "completed", "")));

        assertThatThrownBy(() -> gateway.completeSingle(TOKEN, GRANT_ID))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.API_ERROR);
                assertThat(ex.getMessage()).contains("could not read the answer");
            });
        server.verify();
    }

    @Test
    void aConnectivityFailureNeverEchoesTheCaseTokenThatRidesInTheUrl() {
        // A plain RestTemplate against a closed port: Spring's own message carries the expanded
        // request URL, and the V2 paths carry the case token in that URL.
        ApiClient dead = new ApiClient(new RestTemplate());
        dead.setBasePath("http://127.0.0.1:1");
        dead.setBearerToken(() -> "bearer-123");
        ConnectApiAttachmentsV2Gateway deadGateway = new ConnectApiAttachmentsV2Gateway(new AttachmentGrantsApi(dead),
            GatewayTestSupport.authenticatedSessionStore());

        assertThatThrownBy(() -> deadGateway.createGrant("CASETOKEN-MARKER", "a", 1))
            .isInstanceOf(AttachmentV2Exception.class)
            .satisfies(e -> {
                AttachmentV2Exception ex = (AttachmentV2Exception) e;
                assertThat(ex.code()).isEqualTo(AttachmentV2Exception.CONNECTIVITY);
                assertThat(ex.status()).isZero();
                assertThat(ex.getMessage()).doesNotContain("MARKER").doesNotContain("127.0.0.1");
            });
    }
}

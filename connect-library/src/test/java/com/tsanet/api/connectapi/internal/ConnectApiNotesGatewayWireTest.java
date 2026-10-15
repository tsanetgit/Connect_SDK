package com.tsanet.api.connectapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tsanet.api.ConnectApiException;
import com.tsanet.api.connectapi.dto.CaseNoteDto;
import com.tsanet.api.connectapi.dto.NoteTypeDto;
import com.tsanet.api.generated.api.CaseNotesApi;
import com.tsanet.api.generated.invoker.ApiClient;
import com.tsanet.api.storage.CaseNoteRepository;
import com.tsanet.api.storage.CaseNoteStorageService;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Note types through the generated {@link CaseNotesApi}, the real {@link ApiClient} and the
 * library's own RestTemplate, against a mock server (tsanetgit/Connect-API-Code#200): what a
 * create puts on the wire for "no type" and for a code, how a typed note reads back, the
 * server's order for {@code GET /v1/note-types}, and the server's 400 for a code it doesn't offer.
 */
@ExtendWith(GatewayTestDatabaseExtension.class)
class ConnectApiNotesGatewayWireTest {

    private static final String BASE = "https://connect.example";
    private static final String NOTES = BASE + "/v1/collaboration-requests/tok-1/notes";
    private static final String LIST_NOTES = NOTES + "?includeRemoved=false";

    private MockRestServiceServer server;
    private CaseNoteStorageService storageService;
    private ConnectApiNotesGateway gateway;
    private final List<String> sentBodies = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = ConnectApiRestTemplates.create();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        ApiClient apiClient = new ApiClient(restTemplate);
        apiClient.setBasePath(BASE);
        apiClient.setBearerToken(() -> "bearer-123");
        storageService = new CaseNoteStorageService(
            new CaseNoteRepository(GatewayTestSupport.inMemoryJdbc("notes-gateway-wire-test")));
        gateway = new ConnectApiNotesGateway(new CaseNotesApi(apiClient),
            GatewayTestSupport.authenticatedSessionStore(), storageService);
    }

    /** Expects one create, records its body, answers with {@code createdNote}, then the refresh. */
    private void expectCreate(String createdNote) {
        server.expect(requestTo(NOTES)).andExpect(method(HttpMethod.POST))
            .andExpect(request -> sentBodies.add(((MockClientHttpRequest) request).getBodyAsString()))
            .andRespond(withSuccess(createdNote, MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(LIST_NOTES)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[" + createdNote + "]", MediaType.APPLICATION_JSON));
    }

    @Test
    void noTypeSendsNoTypeReference() throws Exception {
        expectCreate("{\"id\":1,\"token\":\"n1\",\"summary\":\"s\",\"type\":null}");

        gateway.createNote("tok-1", "s", "d", "LOW");

        // The spec stores omitted, "type": null and {"code": null} alike as no type; the one shape it
        // refuses is {}, so a reference must never be sent without its code.
        JSONObject body = new JSONObject(sentBodies.get(0));
        assertThat(body.isNull("type")).as("type must be null or absent, was: %s", sentBodies.get(0)).isTrue();
        server.verify();
    }

    @Test
    void aCodeIsSentAsGivenInsideAReference() throws Exception {
        expectCreate("{\"id\":2,\"token\":\"n2\",\"summary\":\"s\","
            + "\"type\":{\"code\":\"public\",\"displayName\":\"Public\"}}");

        CaseNoteDto created = gateway.createNote("tok-1", "s", "d", "LOW", "PuBlic");

        assertThat(new JSONObject(sentBodies.get(0)).getJSONObject("type").getString("code")).isEqualTo("PuBlic");
        assertThat(created.type()).isEqualTo("public");
        assertThat(created.typeDisplayName()).isEqualTo("Public");
        server.verify();
    }

    @Test
    void anUntypedNoteReadsBackNullInBothFields() {
        server.expect(requestTo(LIST_NOTES)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[{\"id\":3,\"token\":\"n3\",\"type\":null},{\"id\":4,\"token\":\"n4\"}]",
                MediaType.APPLICATION_JSON));

        assertThat(gateway.getNotes("tok-1"))
            .extracting(CaseNoteDto::type, CaseNoteDto::typeDisplayName)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(null, null),
                org.assertj.core.groups.Tuple.tuple(null, null));
        server.verify();
    }

    @Test
    void listNoteTypesKeepsTheServersOrder() {
        server.expect(requestTo(BASE + "/v1/note-types")).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[{\"code\":\"status\",\"displayName\":\"Status\"},"
                + "{\"code\":\"partner\",\"displayName\":\"Partner\"},"
                + "{\"code\":\"public\",\"displayName\":\"Public\"}]", MediaType.APPLICATION_JSON));

        assertThat(gateway.listNoteTypes()).containsExactly(
            new NoteTypeDto("status", "Status"),
            new NoteTypeDto("partner", "Partner"),
            new NoteTypeDto("public", "Public"));
        server.verify();
    }

    @Test
    void aCodeTheServerDoesNotOfferReachesTheCallerAsItsProblem() {
        // The problem+json body Development returned on 2026-10-15 for {"code": "bogus"} when asked with
        // Accept: application/problem+json; the next test is the legacy body it returns otherwise.
        server.expect(requestTo(NOTES)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"type\":\"https://api.tsanet.org/errors/invalid-note-type\",\"title\":\"Invalid Note Type\","
                    + "\"status\":400,\"detail\":\"Invalid note type 'bogus'. Allowed values: partner, public, "
                    + "escalation, status\",\"instance\":\"/v1/collaboration-requests/tok-1/notes\","
                    + "\"field\":\"type\",\"allowedValues\":[\"partner\",\"public\",\"escalation\",\"status\"]}"));

        assertThatThrownBy(() -> gateway.createNote("tok-1", "s", "d", "LOW", "bogus"))
            .isInstanceOfSatisfying(ConnectApiException.class, e -> {
                assertThat(e.kind()).isEqualTo(ConnectApiException.Kind.PROBLEM);
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.type()).isEqualTo("https://api.tsanet.org/errors/invalid-note-type");
                assertThat(e.getMessage()).contains("Allowed values: partner, public, escalation, status");
            });
        assertThat(storageService.findByCaseToken("tok-1")).isEmpty();
        server.verify();
    }

    @Test
    void theLegacyRefusalOfACodeReachesTheCallerWithItsReason() {
        // The legacy body Development returned on 2026-10-15 for {"code": "bogus"}, the shape a caller
        // that doesn't ask for problem+json (this library) receives.
        server.expect(requestTo(NOTES)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"Validation failed\",\"validationErrors\":[{\"field\":\"type\","
                    + "\"message\":\"Invalid note type 'bogus'. Allowed values: partner, public, escalation, status\"}]}"));

        assertThatThrownBy(() -> gateway.createNote("tok-1", "s", "d", "LOW", "bogus"))
            .isInstanceOfSatisfying(ConnectApiException.class, e -> {
                assertThat(e.kind()).isEqualTo(ConnectApiException.Kind.LEGACY);
                assertThat(e.status()).isEqualTo(400);
                assertThat(e.getMessage())
                    .contains("Invalid note type 'bogus'. Allowed values: partner, public, escalation, status");
            });
        assertThat(storageService.findByCaseToken("tok-1")).isEmpty();
        server.verify();
    }
}

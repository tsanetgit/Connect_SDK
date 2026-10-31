package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AttachmentsV2Facade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.WebhooksFacade;
import com.tsanet.demo.web.SessionGuard;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A guest login has a lower upload limit, here 1 MB: any body it sends must declare a length no greater
 * than that, or it's answered before anything is read. The operator isn't held to this. Over real HTTP,
 * with the facades mocked so an upload that gets through shows what the SDK would have received.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "tsanet.demo.auth.username=operator",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.auth.guest.username=visitor",
    "tsanet.demo.auth.guest.password=g",
    "tsanet.demo.auth.guest.max-upload-bytes=1048576",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-guest-limit-${random.uuid}"
})
class GuestUploadLimitTest {

    private static final int LIMIT = 1024 * 1024;
    private static final String TOKEN = "made-up-token";
    private static final String MULTIPART = "multipart/form-data; boundary=abc";
    private static final String V1 = "/api/requests/t/attachments";
    private static final String V2 = "/api/requests/t/attachments/v2";

    @TempDir
    static Path uploads;

    @DynamicPropertySource
    static void uploadLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", uploads::toString);
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private SessionGuard guard;

    private final AttachmentsFacade attachments = mock(AttachmentsFacade.class);
    private final AttachmentsV2Facade attachmentsV2 = mock(AttachmentsV2Facade.class);
    private final CompletableFuture<Long> received = new CompletableFuture<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(guard.session()).thenReturn(session);
        when(session.attachments()).thenReturn(attachments);
        when(session.attachmentsV2()).thenReturn(attachmentsV2);
        when(attachments.forwardAttachments(anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(1)).isEqualTo("the description");
            received.complete(Files.size(call.<List<Path>>getArgument(2).get(0)));
            return List.of();
        });
        when(attachmentsV2.send(anyString(), any(Path.class), any())).thenAnswer(call -> {
            received.complete(Files.size(call.<Path>getArgument(1)));
            return null;
        });
    }

    @AfterEach
    void nothingIsLeftBehind() throws Exception {
        for (int i = 0; i < 100 && !tempFiles().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(tempFiles()).isEmpty();
    }

    private List<Path> tempFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploads)) {
            return files.toList();
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] body(String path, int fileBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes + 512);
        byte[] head = (path.equals(V1)
            ? "--abc\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nthe description\r\n"
                + "--abc\r\nContent-Disposition: form-data; name=\"files\"; filename=\"a.bin\"\r\n\r\n"
            : "--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(head);
        out.writeBytes(new byte[fileBytes]);
        out.writeBytes("\r\n--abc--\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private HttpRequest.Builder upload(String path, String user, String password) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", basic(user, password))
            .header("X-XSRF-TOKEN", TOKEN)
            .header("Cookie", "XSRF-TOKEN=" + TOKEN);
    }

    private HttpResponse<String> declared(String path, String user, String password, int fileBytes) throws Exception {
        return http.send(upload(path, user, password).header("Content-Type", MULTIPART).POST(BodyPublishers.ofByteArray(body(path, fileBytes))).build(),
            BodyHandlers.ofString());
    }

    private HttpResponse<String> chunked(String path, String user, String password, int fileBytes) throws Exception {
        byte[] bytes = body(path, fileBytes);
        InputStream stream = new ByteArrayInputStream(bytes);
        return http.send(upload(path, user, password).header("Content-Type", MULTIPART).POST(BodyPublishers.ofInputStream(() -> stream)).build(),
            BodyHandlers.ofString());
    }

    private void delivered(HttpResponse<String> response, int fileBytes) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(received.get(5, TimeUnit.SECONDS)).isEqualTo(fileBytes);
    }

    @ParameterizedTest
    @CsvSource({V1, V2})
    void aGuestUnderTheLimitGetsThrough(String path) throws Exception {
        delivered(declared(path, "visitor", "g", LIMIT / 2), LIMIT / 2);
    }

    @ParameterizedTest
    @CsvSource({V1, V2})
    void theOperatorIsNotHeldToTheGuestLimit(String path) throws Exception {
        delivered(declared(path, "operator", "op", 2 * LIMIT), 2 * LIMIT);
    }

    @ParameterizedTest
    @CsvSource({V1, V2})
    void theOperatorIsNotHeldToTheGuestLimitWithNoDeclaredLength(String path) throws Exception {
        delivered(chunked(path, "operator", "op", 2 * LIMIT), 2 * LIMIT);
    }

    /** Sends only headers and the start of a body, so an answer means the server didn't wait for the rest. */
    private String answerToHeaders(String method, String path, String contentType, String framing) throws IOException {
        return answerToHeaders("HTTP/1.1", method, path, contentType, framing);
    }

    private String answerToHeaders(String version, String method, String path, String contentType, String framing) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + path + " " + version + "\r\nHost: localhost\r\nContent-Type: " + contentType + "\r\n"
                + (framing.isEmpty() ? "" : framing + "\r\n") + "Authorization: " + basic("visitor", "g") + "\r\n"
                + "X-XSRF-TOKEN: " + TOKEN + "\r\nCookie: XSRF-TOKEN=" + TOKEN + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder answer = new StringBuilder();
            byte[] buffer = new byte[1024];
            int read;
            while (!answer.toString().contains("\"}") && (read = socket.getInputStream().read(buffer)) >= 0) {
                answer.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
            }
            return answer.toString();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "POST," + V1 + ",multipart/form-data; boundary=abc",
        "POST," + V2 + ",multipart/form-data; boundary=abc",
        "POST,/api/settings/beta/credentials,application/json",
        "PUT,/api/settings/beta/e2ee-partners/2087,application/json",
    })
    void aGuestDeclaringTooMuchIsRefusedBeforeAnythingIsRead(String method, String path, String contentType) throws Exception {
        String answer = answerToHeaders(method, path, contentType, "Content-Length: 1048577");

        assertThat(answer).startsWith("HTTP/1.1 413").contains(GuestUploadLimitFilter.OVER_THE_LIMIT);
        assertThat(tempFiles()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "POST," + V1 + ",multipart/form-data; boundary=abc",
        "POST," + V2 + ",multipart/form-data; boundary=abc",
        "POST,/api/settings/beta/credentials,application/json",
        "PUT,/api/settings/beta/e2ee-partners/2087,application/json",
    })
    void aGuestDeclaringNoLengthIsRefusedBeforeAnythingIsRead(String method, String path, String contentType) throws Exception {
        String answer = answerToHeaders(method, path, contentType, "Transfer-Encoding: chunked");

        assertThat(answer).startsWith("HTTP/1.1 411").contains(GuestUploadLimitFilter.NO_LENGTH);
        assertThat(tempFiles()).isEmpty();
    }

    /** No length and no chunked framing, on a method that carries a body. */
    @ParameterizedTest
    @CsvSource({
        "POST," + V2 + ",multipart/form-data; boundary=abc",
        "POST,/api/settings/beta/credentials,application/json",
        "PUT,/api/settings/beta/e2ee-partners/2087,application/json",
        "PATCH,/api/settings/beta/credentials,application/json",
    })
    void aGuestWithNoLengthOnABodyMethodIsRefused(String method, String path, String contentType) throws Exception {
        assertThat(answerToHeaders(method, path, contentType, ""))
            .startsWith("HTTP/1.1 411").contains(GuestUploadLimitFilter.NO_LENGTH);
    }

    /**
     * This pins the status only. Tomcat reads such a request's body as empty anyway, so before this rule it
     * answered 400 with nothing read; the rule makes the answer the same as any other request with no length.
     */
    @Test
    void anHttp10RequestWithNoLengthIsRefused() throws Exception {
        assertThat(answerToHeaders("HTTP/1.0", "POST", V2, MULTIPART, ""))
            .startsWith("HTTP/1.1 411").contains(GuestUploadLimitFilter.NO_LENGTH);
    }

    /** What the page sends: Chromium declares a length of 0 on a bodyless POST or PUT, and none on a DELETE. */
    @Test
    void aGuestsBodylessRequestsFromThePageAreNotRefused() throws Exception {
        TsaNetApiSession session = guard.session();
        when(session.caseResponses()).thenReturn(mock(CaseResponsesFacade.class));
        when(session.webhooks()).thenReturn(mock(WebhooksFacade.class));

        assertThat(http.send(upload("/api/requests/t/close", "visitor", "g").POST(BodyPublishers.noBody()).build(),
            BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(http.send(upload("/api/webhooks/1", "visitor", "g").DELETE().build(),
            BodyHandlers.ofString()).statusCode()).isEqualTo(204);
    }

    /** A declared length and chunked framing are checked on every method, including those that may omit a length. */
    @ParameterizedTest
    @CsvSource({"GET", "DELETE", "OPTIONS"})
    void theOtherChecksApplyToEveryMethod(String method) throws Exception {
        assertThat(answerToHeaders(method, "/api/settings", "application/json", "Content-Length: 1048577"))
            .startsWith("HTTP/1.1 413");
        assertThat(answerToHeaders(method, "/api/settings", "application/json", "Transfer-Encoding: chunked"))
            .startsWith("HTTP/1.1 411");
    }

    /** Exactly the limit is allowed: it's the JSON route's own validation that answers here. */
    @Test
    void aGuestsJsonUnderTheLimitIsRead() throws Exception {
        HttpResponse<String> response = http.send(upload("/api/settings/beta/credentials", "visitor", "g")
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString("{}" + " ".repeat(LIMIT - 2))).build(), BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("username and password are required");
    }

    @Test
    void aGuestsRequestWithNoBodyIsNotHeldToThis() throws Exception {
        assertThat(http.send(upload("/api/settings", "visitor", "g").GET().build(), BodyHandlers.ofString()).statusCode())
            .isEqualTo(200);
    }

    @Test
    void theOperatorsJsonIsNotHeldToTheGuestLimitDeclaredOrNot() throws Exception {
        String body = "{}" + " ".repeat(2 * LIMIT);
        for (BodyPublisher publisher : List.of(BodyPublishers.ofString(body),
                BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))) {
            HttpResponse<String> response = http.send(upload("/api/settings/beta/credentials", "operator", "op")
                .header("Content-Type", "application/json").POST(publisher).build(), BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("username and password are required");
        }
    }

    /** The same parser rules apply to a guest's request as to anyone's. */
    @Test
    void aGuestsMalformedBodyIsABadRequest() throws Exception {
        String partial = "--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\npartial";
        HttpResponse<String> response = http.send(upload(V2, "visitor", "g")
            .header("Content-Type", MULTIPART).POST(BodyPublishers.ofString(partial)).build(), BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("The request is not a valid multipart upload");
    }
}

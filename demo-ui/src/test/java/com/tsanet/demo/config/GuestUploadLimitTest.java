package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AttachmentsV2Facade;
import com.tsanet.demo.web.ApiErrorHandler;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
 * A guest login has a lower upload limit, here 1 MB, on both routes, whether or not the request declares
 * its length. The operator's limit doesn't change. Over real HTTP, with the facades mocked so an upload
 * that gets through shows what the SDK would have received.
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
    private static final String BOUNDARY = "abc";
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

    private long tempBytes() throws IOException {
        long total = 0;
        for (Path file : tempFiles()) {
            try {
                total += Files.size(file);
            } catch (IOException removedMeanwhile) {
                // the file went between the listing and the size
            }
        }
        return total;
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
            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
            .header("Authorization", basic(user, password))
            .header("X-XSRF-TOKEN", TOKEN)
            .header("Cookie", "XSRF-TOKEN=" + TOKEN);
    }

    private HttpResponse<String> declared(String path, String user, String password, int fileBytes) throws Exception {
        return http.send(upload(path, user, password).POST(BodyPublishers.ofByteArray(body(path, fileBytes))).build(),
            BodyHandlers.ofString());
    }

    private HttpResponse<String> chunked(String path, String user, String password, int fileBytes) throws Exception {
        byte[] bytes = body(path, fileBytes);
        InputStream stream = new ByteArrayInputStream(bytes);
        return http.send(upload(path, user, password).POST(BodyPublishers.ofInputStream(() -> stream)).build(),
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
    void aGuestUnderTheLimitGetsThroughWithNoDeclaredLength(String path) throws Exception {
        delivered(chunked(path, "visitor", "g", LIMIT / 2), LIMIT / 2);
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

    /** Reads until the fixed message arrives, or the answer ends. */
    private static String readAnswer(InputStream in) throws IOException {
        StringBuilder answer = new StringBuilder();
        byte[] buffer = new byte[1024];
        int read;
        while (!answer.toString().contains(ApiErrorHandler.UPLOAD_TOO_LARGE) && (read = in.read(buffer)) >= 0) {
            answer.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
        }
        return answer.toString();
    }

    /** It declares far more than it sends, and is answered straight away, so nothing of the body was waited for. */
    @ParameterizedTest
    @CsvSource({V1, V2})
    void aGuestDeclaringTooMuchIsRefusedBeforeAnythingIsRead(String path) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1500);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=abc\r\n"
                + "Content-Length: 209715200\r\nAuthorization: " + basic("visitor", "g") + "\r\n"
                + "X-XSRF-TOKEN: " + TOKEN + "\r\nCookie: XSRF-TOKEN=" + TOKEN + "\r\n\r\n--abc\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(readAnswer(socket.getInputStream())).startsWith("HTTP/1.1 413").contains(ApiErrorHandler.UPLOAD_TOO_LARGE);
        }
        assertThat(tempFiles()).isEmpty();
    }

    /** No declared length: the server reads up to the limit and stops, with temp never past it. */
    @ParameterizedTest
    @CsvSource({V1, V2})
    void aGuestWithNoDeclaredLengthIsCutOffAtTheLimit(String path) throws Exception {
        AtomicLong peak = new AtomicLong();
        AtomicBoolean done = new AtomicBoolean();
        Thread sampler = new Thread(() -> {
            while (!done.get()) {
                try {
                    peak.accumulateAndGet(tempBytes(), Math::max);
                    Thread.sleep(1);
                } catch (IOException | InterruptedException stop) {
                    return;
                }
            }
        });
        sampler.start();
        long sent = 0;
        long willing = 64L * LIMIT;
        String answer;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: multipart/form-data; boundary=abc\r\n"
                + "Transfer-Encoding: chunked\r\nAuthorization: " + basic("visitor", "g") + "\r\n"
                + "X-XSRF-TOKEN: " + TOKEN + "\r\nCookie: XSRF-TOKEN=" + TOKEN + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            byte[] head = body(path, 0);
            head = java.util.Arrays.copyOf(head, head.length - "\r\n--abc--\r\n".length());
            out.write((Integer.toHexString(head.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(head);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[64 * 1024];
            byte[] size = (Integer.toHexString(chunk.length) + "\r\n").getBytes(StandardCharsets.US_ASCII);
            while (sent < willing && in.available() == 0) {
                out.write(size);
                out.write(chunk);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                sent += chunk.length;
            }
            answer = readAnswer(in);
        } finally {
            done.set(true);
            sampler.join();
        }

        assertThat(answer).startsWith("HTTP/1.1 413");
        assertThat(sent).as("the client wasn't made to send all it would").isLessThan(willing);
        assertThat(peak.get()).as("temp stops at the limit").isLessThanOrEqualTo(LIMIT + 64L * 1024);
        assertThat(answer).contains(ApiErrorHandler.UPLOAD_TOO_LARGE);
    }

    /** The same parser rules apply to a guest's request as to anyone's. */
    @Test
    void aGuestsMalformedBodyIsABadRequest() throws Exception {
        String partial = "--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\npartial";
        HttpResponse<String> response = http.send(upload(V2, "visitor", "g")
            .POST(BodyPublishers.ofString(partial)).build(), BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("The request is not a valid multipart upload");
    }
}

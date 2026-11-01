package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the gate on, a request with no valid login is refused before its body is read. Each request is
 * sent over a real socket with a declared length it never finishes: a server that starts reading waits
 * for the rest, so it answers nothing and has a temp file, and a server that refuses first answers
 * straight away with neither.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "tsanet.demo.auth.username=operator",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.auth.guest.username=visitor",
    "tsanet.demo.auth.guest.password=g",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-refuse-${random.uuid}"
})
class RefuseBeforeReadingTest {

    private static final String MULTIPART = "multipart/form-data; boundary=abc";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String TOKEN = "made-up-token";
    private static final int SENT = 256 * 1024;
    private static final long DECLARED = 200L * 1024 * 1024;

    @TempDir
    static Path uploads;

    @DynamicPropertySource
    static void uploadLocation(DynamicPropertyRegistry registry) {
        registry.add("spring.servlet.multipart.location", uploads::toString);
    }

    @LocalServerPort
    private int port;

    private record Answer(String statusLine, String head) {
    }

    /** Sends the headers and the start of a body, then waits for an answer without sending the rest. */
    private Answer partial(String method, String path, String contentType, String authorization, boolean madeUpPair)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(1500);
            StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(DECLARED).append("\r\n");
            if (authorization != null) {
                head.append("Authorization: ").append(authorization).append("\r\n");
            }
            if (madeUpPair) {
                head.append("X-XSRF-TOKEN: ").append(TOKEN).append("\r\nCookie: XSRF-TOKEN=").append(TOKEN).append("\r\n");
            }
            head.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            if (contentType.startsWith("multipart/")) {
                out.write("--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            }
            out.write(new byte[SENT]);
            out.flush();
            StringBuilder answer = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            try {
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    answer.append(line).append('\n');
                }
            } catch (SocketTimeoutException stillReading) {
                return new Answer("no answer: the server is still reading the body", tempFiles().toString());
            }
            return new Answer(answer.toString().lines().findFirst().orElse(""), answer.toString());
        }
    }

    private List<Path> tempFiles() throws IOException {
        try (Stream<Path> files = Files.list(uploads)) {
            return files.toList();
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void refusedUnread(String method, String path, String contentType, String authorization, boolean pair,
                               String status) throws IOException {
        Answer answer = partial(method, path, contentType, authorization, pair);
        assertThat(answer.statusLine()).contains(status);
        assertThat(tempFiles()).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} pair={2} -> {3}")
    @CsvSource({
        // A gated route.
        "POST,/api/requests/t/attachments/v2,false,403",
        "POST,/api/requests/t/attachments/v2,true,401",
        "POST,/api/requests/t/attachments,false,403",
        "POST,/api/requests/t/attachments,true,401",
        "PUT,/api/settings/beta/credentials,true,401",
        // The public paths, for anything but a plain GET.
        "POST,/healthz,false,403",
        "POST,/healthz,true,401",
        "PUT,/healthz,true,401",
        "DELETE,/healthz,true,401",
        "POST,/.well-known/jwks.json,false,403",
        "POST,/.well-known/jwks.json,true,401",
        // A GET stays open, and has no reason to read what it was sent.
        "GET,/healthz,false,200",
        "GET,/.well-known/jwks.json,false,404",
        // Paths the framework itself answers.
        "POST,/error,true,401",
        "GET,/error,false,401",
        "POST,/logout,true,401",
        "POST,/no-such-path,true,401",
    })
    void aMultipartBodyWithNoLoginIsAnsweredUnread(String method, String path, boolean pair, String status) throws IOException {
        refusedUnread(method, path, MULTIPART, null, pair, status);
    }

    @ParameterizedTest(name = "{0} {1} pair={2} -> {3}")
    @CsvSource({
        "POST,/api/requests/t/attachments/v2,false,403",
        "POST,/api/requests/t/attachments/v2,true,401",
        "POST,/healthz,true,401",
    })
    void aMultipartBodyWithAWrongPasswordIsRefusedUnread(String method, String path, boolean pair, String status) throws IOException {
        refusedUnread(method, path, MULTIPART, basic("operator", "wrong"), pair, status);
    }

    /** Other types the framework might read before the login check. */
    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource({
        "PUT,/api/settings/beta/credentials,application/x-www-form-urlencoded,401",
        "PATCH,/api/settings/beta/credentials,application/x-www-form-urlencoded,401",
        "DELETE,/api/webhooks/1,application/x-www-form-urlencoded,401",
        "POST,/api/settings/beta/credentials,application/x-www-form-urlencoded,401",
        "POST,/api/settings/beta/credentials,application/json,401",
        "PUT,/healthz,application/x-www-form-urlencoded,401",
    })
    void aBodyOfAnotherTypeWithNoLoginIsRefusedUnread(String method, String path, String contentType, String status) throws IOException {
        refusedUnread(method, path, contentType, null, true, status);
    }

    @Test
    void aMultipartTypeInAnotherCaseIsLeftUnreadOnAPublicPath() throws IOException {
        refusedUnread("GET", "/healthz", "MULTIPART/FORM-DATA; boundary=abc", null, false, "200");
    }

    /** The control: this harness sees a server read a body, so the refusals above mean something. */
    @ParameterizedTest
    @CsvSource({"/api/requests/t/attachments/v2", "/api/requests/t/attachments"})
    void aLoggedInUploadIsRead(String path) throws Exception {
        for (String[] login : new String[][] {{"operator", "op"}, {"visitor", "g"}}) {
            Answer answer = partial("POST", path, MULTIPART, basic(login[0], login[1]), true);
            assertThat(answer.statusLine()).contains("still reading");
            assertThat(answer.head()).contains("upload_");
            awaitNoTempFiles();
        }
    }

    /** The whole way through: past the gate and the parser, to the demo's "credentials not configured". */
    @ParameterizedTest
    @CsvSource({"/api/requests/t/attachments/v2,file", "/api/requests/t/attachments,files"})
    void aLoggedInUploadReachesTheController(String path, String part) throws Exception {
        String body = "--abc\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nd\r\n"
            + "--abc\r\nContent-Disposition: form-data; name=\"" + part + "\"; filename=\"a.txt\"\r\n\r\n"
            + "x".repeat(5 * 1024 * 1024) + "\r\n--abc--\r\n";
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", MULTIPART)
                .header("Authorization", basic("visitor", "g"))
                .header("X-XSRF-TOKEN", TOKEN)
                .header("Cookie", "XSRF-TOKEN=" + TOKEN)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(428);
        awaitNoTempFiles();
    }

    @Test
    void aPlainGetOfThePublicPathsNeedsNoLogin() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (String[] row : new String[][] {{"/healthz", "200"}, {"/.well-known/jwks.json", "404"}}) {
            int status = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + row[0])).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
            assertThat(status).as(row[0]).isEqualTo(Integer.parseInt(row[1]));
        }
        int head = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/healthz"))
                .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
            java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        assertThat(head).as("open to GET only").isEqualTo(401);
    }

    /** Tomcat removes a part's temp file when it finishes with the request, a moment after the socket closes. */
    private void awaitNoTempFiles() throws Exception {
        for (int i = 0; i < 100 && !tempFiles().isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(tempFiles()).isEmpty();
    }

    /** A malformed body with no token gets the same JSON refusal as any other, since its body is never read. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "multipart/form-data|x",
        "multipart/form-data; boundary=abc|--abc",
        "multipart/form-data; boundary=|x",
    })
    void aMalformedMultipartWithNoTokenGetsTheJsonRefusal(String contentType, String body, CapturedOutput output) throws Exception {
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/requests/t/attachments/v2"))
                .header("Content-Type", contentType)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).isEqualTo(SecurityConfig.CSRF_REFUSED);
        assertThat(output).doesNotContain(" ERROR ");
    }

    @Test
    void aRefusalSetsNoSession() throws IOException {
        assertThat(partial("POST", "/api/requests/t/attachments/v2", MULTIPART, null, true).head()).doesNotContain("JSESSIONID");
    }
}

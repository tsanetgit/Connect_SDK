package com.tsanet.demo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Upload requests through the real server, because Tomcat is what parses a multipart body. Each row of
 * a malformed request answers 400 with a fixed message; a body over the limit keeps its 413.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.servlet.multipart.max-file-size=100KB",
    "spring.servlet.multipart.max-request-size=100KB",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-multipart-errors-${random.uuid}"
})
class MultipartErrorsTest {

    private static final String MALFORMED = "The request is not a valid multipart upload";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> upload(String contentType, byte[] body) throws Exception {
        String base = "http://127.0.0.1:" + port;
        String token = http.send(HttpRequest.newBuilder(URI.create(base + "/api/settings")).build(), BodyHandlers.ofString())
            .headers().allValues("set-cookie").stream()
            .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
            .map(cookie -> cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';')))
            .findFirst().orElseThrow();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/requests/T1/attachments/v2"))
            .header("X-XSRF-TOKEN", token)
            .header("Cookie", "XSRF-TOKEN=" + token)
            .POST(body == null ? BodyPublishers.noBody() : BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        return http.send(request.build(), BodyHandlers.ofString());
    }

    private static void refused(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains(MALFORMED);
    }

    @Test
    void aJsonBodyIsABadRequest() throws Exception {
        refused(upload("application/json", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void noContentTypeIsABadRequest() throws Exception {
        refused(upload(null, null));
    }

    @Test
    void multipartWithNoBoundaryIsABadRequest() throws Exception {
        refused(upload("multipart/form-data", "x".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMultipartBodyThatEndsEarlyIsABadRequest() throws Exception {
        String partial = "--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\npartial";
        refused(upload("multipart/form-data; boundary=abc", partial.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMultipartWithNoFilePartKeepsSpringsAnswer() throws Exception {
        String empty = "--abc\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\n1\r\n--abc--\r\n";
        HttpResponse<String> response = upload("multipart/form-data; boundary=abc", empty.getBytes(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Required part 'file' is not present.");
    }

    @Test
    void aFileOverTheLimitKeepsSpringsStatus() throws Exception {
        String head = "--abc\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\n";
        String body = head + "x".repeat(200 * 1024) + "\r\n--abc--\r\n";

        assertThat(upload("multipart/form-data; boundary=abc", body.getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(413);
    }
}

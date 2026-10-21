package com.tsanet.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.api.attachments.v2.StorageConfig;
import com.tsanet.api.attachments.v2.StorageTarget;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A session opened through {@link TsaNetApi#initialize} exposes the receiver storage client, and
 * its calls carry the session's bearer. The gateway tests build the gateway directly, so they
 * can't see the runtime leaving it out; this drives a real session against a local server.
 */
class TsaNetApiAttachmentStorageWiringTest {

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String base;

    @TempDir
    Path tmp;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(request + " " + exchange.getRequestHeaders().getFirst("Authorization"));
        switch (request) {
            case "POST /v1/login" -> respond(exchange, 200, "application/json",
                "{\"accessToken\":\"bearer-1\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}");
            case "GET /v1/me" -> respond(exchange, 200, "application/json",
                "{\"company\":{\"id\":202,\"name\":\"Receiver\"},"
                    + "\"user\":{\"id\":1,\"username\":\"receiver@example.test\",\"email\":\"receiver@example.test\"}}");
            case "GET /v2/attachments/storage-config" -> respond(exchange, 200, "application/json",
                "{\"method\":\"azureBlob\",\"azureBlob\":{\"container\":\"inbound\",\"tenantId\":\"tenant-1\","
                    + "\"storageAccountName\":\"acmestore\"},\"lastVerificationStatus\":\"never_tested\"}");
            default -> respond(exchange, 404, "application/problem+json",
                "{\"title\":\"Not Found\",\"status\":404}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void aSessionReadsItsCompanysStorageConfigurationWithItsBearer() {
        TsaNetApiSession session = TsaNetApi.initialize(TsaNetApiConfiguration.forAccount(base,
            tmp.resolve("cache.db").toString(), "default", new PasswordAuthConfig("receiver@example.test", "pw")));
        session.auth().login("receiver@example.test", "pw");
        requests.clear();

        StorageConfig config = session.attachmentStorage().get().orElseThrow();

        assertThat(config.target()).isEqualTo(new StorageTarget.AzureBlob("inbound", "tenant-1", "acmestore", null));
        assertThat(requests).containsExactly("GET /v2/attachments/storage-config Bearer bearer-1");
    }
}

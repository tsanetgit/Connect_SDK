package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.tsanet.demo.E2eeTestVectors;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two configured workspaces on one deploy, through the real gate (tsanetgit/Connect_SDK#190). Each
 * workspace is seeded with its own member, and a stand-in for Connect on loopback answers sign-in,
 * {@code /v1/me} and the case list by which member is asking, the way the platform separates
 * companies. What one workspace's login does must not show for the other, or for the operator.
 */
@SpringBootTest(properties = {
    "tsanet.demo.auth.username=operator",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.allow-insecure-http=true",  // the stand-in below is plain http on loopback
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-workspaces-test-${random.uuid}"
})
class WorkspaceIsolationTest {

    private static final String WORKSPACES = """
        [{"name": "team-a", "username": "team-a", "password": "a-gate-pw",
          "seeds": {"beta": {"username": "a@example.test", "password": "a-member-pw"}}},
         {"name": "team-b", "username": "team-b", "password": "b-gate-pw",
          "seeds": {"beta": {"username": "b@example.test", "password": "b-member-pw"}}}]""";

    private static final String TEAM_A = basic("team-a", "a-gate-pw");
    private static final String TEAM_B = basic("team-b", "b-gate-pw");
    private static final String OPERATOR = basic("operator", "op");

    private static final HttpServer CONNECT = connect();

    /** Signs a member in with a token that names it, then answers each call for the member the token names. */
    private static HttpServer connect() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = exchange.getRequestURI().getPath();
                String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                boolean a = path.equals("/v1/login")
                    ? "a@example.test".equals(JsonMapper.shared().readTree(sent).path("username").asString())
                    : "Bearer token-of-a".equals(bearer);
                String body;
                if (path.equals("/v1/login")) {
                    body = "{\"accessToken\":\"token-of-" + (a ? "a" : "b") + "\",\"tokenType\":\"Bearer\",\"expiresIn\":3600}";
                } else if (path.equals("/v1/me")) {
                    body = "{\"company\":{\"id\":" + (a ? 101 : 202) + ",\"name\":\"Company " + (a ? "A" : "B") + "\"},"
                        + "\"user\":{\"id\":1,\"username\":\"u\",\"email\":\"u@example.test\"}}";
                } else if (path.endsWith("/collaboration-requests/list") || path.endsWith("/collaboration-requests")) {
                    body = "[{\"token\":\"case-of-" + (a ? "a" : "b") + "\",\"summary\":\"A case of company " + (a ? "A" : "B") + "\"}]";
                } else {
                    body = "[]";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopConnect() {
        CONNECT.stop(0);
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        String connect = "http://127.0.0.1:" + CONNECT.getAddress().getPort();
        registry.add("tsanet.demo.environments.beta.api-base-url", () -> connect);
        registry.add("tsanet.demo.environments.dev.api-base-url", () -> connect);
        registry.add("tsanet.demo.e2ee.private-jwks", () -> E2eeTestVectors.load().memberAPrivateJwks());
        registry.add("tsanet.demo.workspaces.definitions", () -> WORKSPACES);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private DemoProperties properties;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** The request as the page sends it: the login, and the cross-site token as cookie and header. */
    private <B extends AbstractMockHttpServletRequestBuilder<B>> B asThePage(B request, String login) throws Exception {
        Cookie cookie = mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, login))
            .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return request.header(HttpHeaders.AUTHORIZATION, login).cookie(cookie).header("X-XSRF-TOKEN", cookie.getValue());
    }

    @Test
    void eachWorkspaceIsSignedInAsItsOwnCompany() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, TEAM_A))
            .andExpect(status().isOk()).andExpect(jsonPath("$.companyId").value(101));
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, TEAM_B))
            .andExpect(status().isOk()).andExpect(jsonPath("$.companyId").value(202));
        // The operator's own workspace has no seed here, so it is signed in as nobody: 428, not a team's company.
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, OPERATOR))
            .andExpect(status().isPreconditionRequired());
    }

    @Test
    void neitherDashboardListsTheOtherWorkspacesCases() throws Exception {
        mvc.perform(get("/api/requests").header(HttpHeaders.AUTHORIZATION, TEAM_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].token").value("case-of-a"));
        mvc.perform(get("/api/requests").header(HttpHeaders.AUTHORIZATION, TEAM_B))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].token").value("case-of-b"));
    }

    @Test
    void switchingTheEnvironmentStaysInItsWorkspace() throws Exception {
        mvc.perform(asThePage(post("/api/settings/environment"), TEAM_A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"dev\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.activeEnvironment").value("dev"));
        try {
            mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, TEAM_B))
                .andExpect(jsonPath("$.activeEnvironment").value("beta"));
            mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, OPERATOR))
                .andExpect(jsonPath("$.activeEnvironment").value("beta"));
        } finally {
            mvc.perform(asThePage(post("/api/settings/environment"), TEAM_A)
                .contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"beta\"}"));
        }
    }

    @Test
    void aPartnerTableChangeStaysInItsWorkspace() throws Exception {
        String partnerKeys = JsonMapper.shared().writeValueAsString(
            java.util.Map.of("jwks", E2eeTestVectors.load().publicJwks("memberB")));
        mvc.perform(asThePage(put("/api/settings/beta/e2ee-partners/" + E2eeTestVectors.MEMBER_B), TEAM_A)
                .contentType(MediaType.APPLICATION_JSON).content(partnerKeys))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.environments[?(@.key == 'beta')].e2eePartners[0].companyId").value(E2eeTestVectors.MEMBER_B));

        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, TEAM_B))
            .andExpect(jsonPath("$.environments[?(@.key == 'beta')].e2eePartners").doesNotExist());
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, OPERATOR))
            .andExpect(jsonPath("$.environments[?(@.key == 'beta')].e2eePartners").doesNotExist());
    }

    @Test
    void anUploadsStatusIsUnknownFromAnotherWorkspace() throws Exception {
        String started = mvc.perform(asThePage(multipart("/api/requests/case-of-a/attachments/v2")
                .file(new MockMultipartFile("file", "report.txt", "text/plain", new byte[] {1})), TEAM_A)
                // A workspace login must declare a length, as a browser does; MockMvc declares the content's.
                .content(new byte[] {1}))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(started, "$.uploadId");

        mvc.perform(get("/api/uploads/" + uploadId).header(HttpHeaders.AUTHORIZATION, TEAM_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uploadId").value(uploadId));
        mvc.perform(get("/api/uploads/" + uploadId).header(HttpHeaders.AUTHORIZATION, TEAM_B))
            .andExpect(status().isNotFound());
    }

    @Test
    void aWorkspaceLoginIsHeldToTheUploadLimitWithNoGuestLoginConfigured() throws Exception {
        // MockMvc declares no length for a bodyless POST. The limit refuses that from a limited login alone.
        mvc.perform(asThePage(post("/api/requests/case-of-a/close"), TEAM_A))
            .andExpect(status().isLengthRequired());
        mvc.perform(asThePage(post("/api/requests/case-of-a/close"), OPERATOR))
            .andExpect(status().isPreconditionRequired());
    }

    @Test
    void eachWorkspaceKeepsItsFilesInItsOwnDirectory() throws Exception {
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, TEAM_A)).andExpect(status().isOk());
        Path dataDir = Path.of(properties.dataDir());

        assertThat(dataDir.resolve("workspaces/team-a/credentials-beta.properties")).exists();
        assertThat(dataDir.resolve("workspaces/team-b/credentials-beta.properties")).exists();
        assertThat(dataDir.resolve("workspaces/team-a/data-beta.db")).exists();
        // Nothing of a team's is kept where the operator's workspace keeps its own.
        assertThat(dataDir.resolve("credentials-beta.properties")).doesNotExist();
        assertThat(Files.readString(dataDir.resolve("workspaces/team-a/credentials-beta.properties"))).contains("a@example.test");
    }
}

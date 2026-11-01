package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The real security filter chain with both logins configured. The gate is the
 * property under test, so this boots the app context rather than a standalone
 * controller, and MockMvc is built from it with the security filter chain in
 * front (the slice-test module that would do this is not a dependency here).
 * The data dir is pointed at a throwaway directory so nothing under the
 * developer's home is touched.
 */
@SpringBootTest(properties = {
    "tsanet.demo.auth.username=shawn",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.auth.guest.username=oracle",
    "tsanet.demo.auth.guest.password=g",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-gate-test-${random.uuid}"
})
class GateFilterChainTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void healthStaysOpen() throws Exception {
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }

    @Test
    void theJwksPathIsOutsideTheGateEvenWithE2eeOff() throws Exception {
        // With no keys the endpoint is a 404, not the gate's 401: the exemption holds without keys,
        // and a 404 is what the path answered before it existed (tsanetgit/Connect_SDK#149).
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isNotFound());
    }

    @Test
    void anonymousIsRefused() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void operatorSignsIn() throws Exception {
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, basic("shawn", "op")))
            .andExpect(status().isOk());
    }

    @Test
    void guestSignsIn() throws Exception {
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, basic("oracle", "g")))
            .andExpect(status().isOk());
    }

    @Test
    void guestUsernameIsCaseInsensitive() throws Exception {
        // The in-memory store keys users by lower-cased name, which is why the
        // duplicate-username check in DemoAuthProperties ignores case.
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, basic("ORACLE", "g")))
            .andExpect(status().isOk());
    }

    @Test
    void guestWithTheOperatorPasswordIsRefused() throws Exception {
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, basic("oracle", "op")))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongGuestPasswordIsRefused() throws Exception {
        mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, basic("oracle", "wrong")))
            .andExpect(status().isUnauthorized());
    }

    private Cookie csrfCookie(String authorization) throws Exception {
        Cookie cookie = mvc.perform(get("/api/settings").header(HttpHeaders.AUTHORIZATION, authorization))
            .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void theFirstResponseSetsACookieThePageCanRead() throws Exception {
        Cookie cookie = mvc.perform(get("/healthz")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"shawn,op", "oracle,g"})
    void aCrossSiteFormIsRefusedEvenWithTheBrowsersCachedLogin(String user, String password) throws Exception {
        // What another site's form sends: the login and the cookie, which the browser adds, and no header.
        String login = basic(user, password);
        mvc.perform(post("/api/requests/t1/close")
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(csrfCookie(login))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(status().isForbidden())
            .andExpect(content().json(SecurityConfig.CSRF_REFUSED));
    }

    @ParameterizedTest
    @CsvSource({"shawn,op", "oracle,g"})
    void theSpasOwnCallPassesTheCheck(String user, String password) throws Exception {
        // 428: past the CSRF check and in the controller, which has no member credentials here.
        String login = basic(user, password);
        Cookie cookie = csrfCookie(login);
        mvc.perform(post("/api/requests/t1/close")
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(cookie)
                .content(new byte[0]) // a browser declares a length of 0 on a bodyless POST
                .header("X-XSRF-TOKEN", cookie.getValue()))
            .andExpect(status().isPreconditionRequired());
    }

    /** The form the parameter was read in before: XorCsrfTokenRequestAttributeHandler's mask of the cookie's value. */
    static String maskedToken(String token) {
        byte[] plain = token.getBytes(StandardCharsets.UTF_8);
        byte[] masked = new byte[plain.length * 2];
        for (int i = 0; i < plain.length; i++) {
            masked[i] = (byte) (i + 1);
            masked[plain.length + i] = (byte) (plain[i] ^ masked[i]);
        }
        return Base64.getUrlEncoder().encodeToString(masked);
    }

    @Test
    void aTokenInTheFormParameterIsNotRead() throws Exception {
        mvc.perform(post("/api/requests/t1/close")
                .header(HttpHeaders.AUTHORIZATION, basic("shawn", "op"))
                .cookie(new Cookie("XSRF-TOKEN", "matching-token"))
                .param("_csrf", maskedToken("matching-token")))
            .andExpect(status().isForbidden())
            .andExpect(content().string(SecurityConfig.CSRF_REFUSED));
    }

    @Test
    void aHeaderThatDoesNotMatchTheCookieIsRefused() throws Exception {
        String login = basic("shawn", "op");
        mvc.perform(post("/api/requests/t1/close")
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(csrfCookie(login))
                .header("X-XSRF-TOKEN", "not-the-cookies-token"))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"/api/requests/t1/attachments,files", "/api/requests/t1/attachments/v2,file"})
    void anUploadIsRefusedWithoutTheHeaderAndPassesWithIt(String path, String part) throws Exception {
        String login = basic("shawn", "op");
        Cookie cookie = csrfCookie(login);
        MockMultipartFile file = new MockMultipartFile(part, "a.txt", "text/plain", new byte[] {1});
        mvc.perform(multipart(path).file(file)
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(cookie))
            .andExpect(status().isForbidden());
        mvc.perform(multipart(path).file(file)
                .param("description", "the form's own field on the V1 route")
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(cookie)
                .header("X-XSRF-TOKEN", cookie.getValue()))
            .andExpect(status().isPreconditionRequired());
    }

    @Test
    void onlyACsrfRefusalAsksForAReload() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.refuse(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo(SecurityConfig.FORBIDDEN);
    }

    @Test
    void theStorageTestIsRefusedWithoutTheHeader() throws Exception {
        String login = basic("shawn", "op");
        mvc.perform(post("/api/settings/beta/receiver-storage/test")
                .header(HttpHeaders.AUTHORIZATION, login)
                .cookie(csrfCookie(login)))
            .andExpect(status().isForbidden());
    }
}

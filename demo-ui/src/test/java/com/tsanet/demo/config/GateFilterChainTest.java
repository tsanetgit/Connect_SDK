package com.tsanet.demo.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
}

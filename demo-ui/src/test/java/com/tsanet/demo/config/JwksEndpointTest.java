package com.tsanet.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.tsanet.demo.E2eeTestVectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The real app with the gate on and the demo's keys set (Member A's throwaway set from the
 * Addendum D vectors): {@code /.well-known/jwks.json} answers without credentials and holds no
 * private member, while everything else still needs the login. The security filter chain is the
 * property under test, so this boots the context, as {@link GateFilterChainTest} does.
 */
@SpringBootTest(properties = {
    "tsanet.demo.auth.username=shawn",
    "tsanet.demo.auth.password=op",
    "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-jwks-test-${random.uuid}"
})
class JwksEndpointTest {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("tsanet.demo.e2ee.private-jwks", () -> E2eeTestVectors.load().memberAPrivateJwks());
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private E2eeKeys keys;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @Test
    void theKeysLoadedFromTheProperty() {
        assertThat(keys.enabled()).isTrue();
    }

    @Test
    void thePublicKeysAreServedOutsideTheGateWithNoPrivateMember() throws Exception {
        String body = mvc.perform(get("/.well-known/jwks.json"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn().getResponse().getContentAsString();

        E2eeTestVectors v = E2eeTestVectors.load();
        JWKSet published = JWKSet.parse(body);
        assertThat(published.getKeys()).hasSize(2);
        for (JWK key : published.getKeys()) {
            assertThat(key.isPrivate()).as("%s", key.getKeyID()).isFalse();
        }
        assertThat(published.getKeyByKeyId(v.rsa("memberA_enc").getKeyID())).isNotNull();
        assertThat(published.getKeyByKeyId(v.ec("memberA_sig").getKeyID())).isNotNull();
        assertThat(body).doesNotContain("\"d\"");
    }

    @Test
    void theRestStaysBehindTheGate() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/healthz")).andExpect(status().isOk());
    }
}

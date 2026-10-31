package com.tsanet.demo.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With the gate off (a local run) a page on another site can still post to localhost, so the check stays on. */
@SpringBootTest(properties = "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-csrf-off-test-${random.uuid}")
class CsrfWithoutGateTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Test
    void aCrossSiteFormIsRefusedWithTheGateOff() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        mvc.perform(post("/api/requests/t1/close")).andExpect(status().isForbidden());
    }
}

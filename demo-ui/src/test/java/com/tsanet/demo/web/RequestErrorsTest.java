package com.tsanet.demo.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Requests Spring rejects before any controller code runs (tsanetgit/Connect_SDK#157). This boots the
 * app context, because an unknown path only reaches the advice through the real resource handler. The
 * security filter chain isn't added: the gate isn't the property under test.
 */
@SpringBootTest(properties = "tsanet.demo.data-dir=${java.io.tmpdir}/tsanet-demo-request-errors-${random.uuid}")
class RequestErrorsTest {

    private static final String CREDENTIALS = "/api/settings/beta/credentials";
    private static final String PARTNER = "/api/settings/beta/e2ee-partners/2087";
    private static final String BODY_MESSAGE = "The request body is missing or does not match the expected JSON";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** The parser's text and the demo's class names stay out of what the caller reads. */
    private static void bodyRefused(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(BODY_MESSAGE));
    }

    @Test
    void aBodyThatIsNotJsonIsABadRequest() throws Exception {
        bodyRefused(mvc.perform(post(CREDENTIALS).contentType(MediaType.APPLICATION_JSON).content("{not json")));
    }

    @Test
    void anUnsupportedContentTypeKeepsItsStatus() throws Exception {
        mvc.perform(post(CREDENTIALS).contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("is not supported")));
    }

    /** Well-formed, but it can't bind: {@code encrypt} is a Boolean on the note body. */
    @Test
    void wellFormedJsonThatCannotBindIsABadRequest() throws Exception {
        bodyRefused(mvc.perform(post("/api/requests/any-token/notes").contentType(MediaType.APPLICATION_JSON)
            .content("{\"encrypt\":\"maybe\"}")));
    }

    @Test
    void aMissingBodyIsABadRequestWithoutAMethodSignature() throws Exception {
        bodyRefused(mvc.perform(post(CREDENTIALS).contentType(MediaType.APPLICATION_JSON)));
    }

    @Test
    void anUnsupportedMethodKeepsItsStatusAndSaysWhatIsAllowed() throws Exception {
        mvc.perform(put(CREDENTIALS).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.containsString("POST")))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PUT")));
    }

    @Test
    void theEnvironmentSwitchRefusesABadBodyTheSameWay() throws Exception {
        bodyRefused(mvc.perform(post("/api/settings/environment").contentType(MediaType.APPLICATION_JSON).content("{not json")));
    }

    @Test
    void anUnknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("api/does-not-exist")));
    }

    @Test
    void theControllersOwnValidationIsUnchanged() throws Exception {
        mvc.perform(post(CREDENTIALS).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("username and password are required"));
    }

    @Test
    void theE2eePartnerEndpointsRefuseTheSameWay() throws Exception {
        bodyRefused(mvc.perform(put(PARTNER).contentType(MediaType.APPLICATION_JSON).content("{not json")));
        bodyRefused(mvc.perform(put(PARTNER).contentType(MediaType.APPLICATION_JSON)));
        mvc.perform(put(PARTNER).contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(PARTNER).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void aNumberThatIsNotANumberIsABadRequest() throws Exception {
        mvc.perform(delete("/api/webhooks/not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not-a-number")))
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("java.lang"))));
    }

    @Test
    void anUploadWithNoFileIsABadRequest() throws Exception {
        mvc.perform(multipart("/api/requests/any-token/attachments/v2"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("file")));
    }
}

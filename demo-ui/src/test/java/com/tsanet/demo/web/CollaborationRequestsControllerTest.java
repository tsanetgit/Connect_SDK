package com.tsanet.demo.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestFormTemplateDto;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pins the create paths to the flag-carrying facade overloads: both modes must
 * pass testSubmission explicitly (the shawn-tsanet/connect-sdk-demo#8 fix) so
 * the module cannot regress onto the deprecated overloads scheduled for
 * removal at the next release boundary.
 */
class CollaborationRequestsControllerTest {

    private CollaborationRequestsFacade requests;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        requests = mock(CollaborationRequestsFacade.class);
        TsaNetApiSession session = mock(TsaNetApiSession.class);
        when(session.collaborationRequests()).thenReturn(requests);
        SessionGuard guard = mock(SessionGuard.class);
        when(guard.session()).thenReturn(session);
        mvc = MockMvcBuilders.standaloneSetup(new CollaborationRequestsController(guard))
            .setControllerAdvice(new ApiErrorHandler())
            .build();
    }

    @Test
    void itRejectsACreateWithNeitherFormNorReceiver() throws Exception {
        mvc.perform(post("/api/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseNumber\":\"C-1\",\"summary\":\"s\",\"description\":\"d\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(requests);
    }

    @Test
    void itCreatesViaTheFormTemplateAsAnExplicitTestSubmission() throws Exception {
        mvc.perform(post("/api/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"formTemplate\":{},\"caseNumber\":\"C-1\",\"summary\":\"s\",\"description\":\"d\"}"))
            .andExpect(status().isOk());
        // Absent customFieldValues must arrive as an empty map, and the test
        // flag must be passed explicitly.
        verify(requests).createRequest(
            any(CollaborationRequestFormTemplateDto.class), eq("C-1"), eq("s"), eq("d"), eq(Map.of()), eq(true));
    }

    @Test
    void itCreatesViaReceiverCompanyIdAsAnExplicitTestSubmission() throws Exception {
        mvc.perform(post("/api/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverCompanyId\":42,\"caseNumber\":\"C-1\",\"summary\":\"s\",\"description\":\"d\"}"))
            .andExpect(status().isOk());
        verify(requests).createRequest(eq(42L), eq("C-1"), eq("s"), eq("d"), eq(true));
    }
}

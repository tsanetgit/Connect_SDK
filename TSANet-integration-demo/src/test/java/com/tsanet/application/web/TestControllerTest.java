package com.tsanet.application.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.connectapi.dto.CollaborationRequestStatusDto;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TestController.class)
class TestControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TsaNetApiSession tsaNetApiSession;

    @MockBean
    private AuthFacade authFacade;

    @MockBean
    private CollaborationRequestsFacade collaborationRequestsFacade;

    @BeforeEach
    void setUp() {
        when(tsaNetApiSession.auth()).thenReturn(authFacade);
        when(tsaNetApiSession.collaborationRequests()).thenReturn(collaborationRequestsFacade);
    }

    @Test
    void itLogsInAndReturnsCollaborationRequestsAsJson() throws Exception {
        when(collaborationRequestsFacade.listRequests()).thenReturn(
            List.of(new CollaborationRequestStatusDto(
                1L,
                "OPEN",
                "Need help",
                "Acme",
                10L,
                "Beta",
                20L,
                "tok1",
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z"
            ))
        );

        mockMvc.perform(get("/test"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                [
                  {
                    "id": 1,
                    "status": "OPEN",
                    "summary": "Need help",
                    "submitCompanyName": "Acme",
                    "submitCompanyId": 10,
                    "receiveCompanyName": "Beta",
                    "receiveCompanyId": 20,
                    "token": "tok1",
                    "createdAt": "2026-01-01T00:00:00Z",
                    "updatedAt": "2026-01-02T00:00:00Z"
                  }
                ]
                """));

        verify(authFacade).loginWithConfiguredCredentials();
        verify(collaborationRequestsFacade).listRequests();
    }
}

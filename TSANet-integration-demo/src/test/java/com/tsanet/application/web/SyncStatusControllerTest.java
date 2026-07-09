package com.tsanet.application.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import com.tsanet.application.sync.ConnectApiPollingCoordinator;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SyncStatusController.class)
class SyncStatusControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ConnectApiPollingCoordinator pollingCoordinator;

    @Test
    void itReturnsLastPollingSnapshot() throws Exception {
        when(pollingCoordinator.lastPollAt()).thenReturn(java.util.Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(pollingCoordinator.lastSnapshots()).thenReturn(
            Map.of(
                "api@appko.com",
                new CommunicationSyncSnapshot(2, 5, 1, 2, 1, true)
            )
        );

        mockMvc.perform(get("/api/sync/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accounts['api@appko.com'].requests").value(2))
            .andExpect(jsonPath("$.accounts['api@appko.com'].notes").value(5))
            .andExpect(jsonPath("$.accounts['api@appko.com'].responses").value(1));
    }

    @Test
    void itTriggersManualPoll() throws Exception {
        when(pollingCoordinator.lastPollAt()).thenReturn(java.util.Optional.empty());
        when(pollingCoordinator.lastSnapshots()).thenReturn(Map.of());

        mockMvc.perform(post("/api/sync/poll"))
            .andExpect(status().isOk());

        verify(pollingCoordinator).pollConfiguredAccounts();
    }
}

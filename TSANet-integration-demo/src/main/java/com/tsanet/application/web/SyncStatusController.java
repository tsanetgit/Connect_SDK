package com.tsanet.application.web;

import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import com.tsanet.application.sync.ConnectApiPollingCoordinator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncStatusController {
    private final ConnectApiPollingCoordinator pollingCoordinator;

    public SyncStatusController(ConnectApiPollingCoordinator pollingCoordinator) {
        this.pollingCoordinator = pollingCoordinator;
    }

    @GetMapping("/status")
    public ResponseEntity<SyncStatusResponse> status() {
        Map<String, SnapshotView> accounts = new LinkedHashMap<>();
        for (Map.Entry<String, CommunicationSyncSnapshot> entry : pollingCoordinator.lastSnapshots().entrySet()) {
            accounts.put(entry.getKey(), SnapshotView.from(entry.getValue()));
        }
        return ResponseEntity.ok(new SyncStatusResponse(pollingCoordinator.lastPollAt().orElse(null), accounts));
    }

    @PostMapping("/poll")
    public ResponseEntity<SyncStatusResponse> pollNow() {
        pollingCoordinator.pollConfiguredAccounts();
        return status();
    }

    public record SyncStatusResponse(Instant lastPollAt, Map<String, SnapshotView> accounts) {
    }

    public record SnapshotView(
        int requests,
        int notes,
        int responses,
        int attachmentConfigs,
        int webhooks,
        boolean userSynced
    ) {
        static SnapshotView from(CommunicationSyncSnapshot snapshot) {
            return new SnapshotView(
                snapshot.requestCount(),
                snapshot.noteCount(),
                snapshot.responseCount(),
                snapshot.attachmentConfigCount(),
                snapshot.webhookSubscriptionCount(),
                snapshot.userContextSynced()
            );
        }
    }
}

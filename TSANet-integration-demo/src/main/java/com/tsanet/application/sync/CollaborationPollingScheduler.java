package com.tsanet.application.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "tsanet.sync", name = "enabled", havingValue = "true")
public class CollaborationPollingScheduler {
    private final ConnectApiPollingCoordinator pollingCoordinator;

    public CollaborationPollingScheduler(ConnectApiPollingCoordinator pollingCoordinator) {
        this.pollingCoordinator = pollingCoordinator;
    }

    @Scheduled(
        initialDelayString = "${tsanet.sync.initial-delay-ms:10000}",
        fixedDelayString = "${tsanet.sync.interval-ms:60000}"
    )
    public void pollCommunicationContext() {
        pollingCoordinator.pollConfiguredAccounts();
    }
}

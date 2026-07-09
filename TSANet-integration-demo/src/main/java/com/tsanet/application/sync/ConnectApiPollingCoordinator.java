package com.tsanet.application.sync;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
import com.tsanet.application.config.TsaNetApplicationProperties;
import com.tsanet.application.config.TsaNetScenarioProperties;
import com.tsanet.application.config.TsaNetSyncProperties;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConnectApiPollingCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ConnectApiPollingCoordinator.class);

    private final TsaNetApiSessionFactory sessionFactory;
    private final TsaNetApplicationProperties applicationProperties;
    private final TsaNetScenarioProperties scenarioProperties;
    private final TsaNetSyncProperties syncProperties;

    private volatile Instant lastPollAt;
    private volatile Map<String, CommunicationSyncSnapshot> lastSnapshots = Map.of();

    public ConnectApiPollingCoordinator(
        TsaNetApiSessionFactory sessionFactory,
        TsaNetApplicationProperties applicationProperties,
        TsaNetScenarioProperties scenarioProperties,
        TsaNetSyncProperties syncProperties
    ) {
        this.sessionFactory = sessionFactory;
        this.applicationProperties = applicationProperties;
        this.scenarioProperties = scenarioProperties;
        this.syncProperties = syncProperties;
    }

    public void pollConfiguredAccounts() {
        List<PollingAccountRegistry.PollingAccount> accounts = PollingAccountRegistry.resolve(
            applicationProperties,
            scenarioProperties,
            syncProperties.pollScenarioAccounts()
        );
        if (accounts.isEmpty()) {
            log.warn("Polling skipped: no accounts configured");
            return;
        }

        Map<String, CommunicationSyncSnapshot> snapshots = new LinkedHashMap<>();
        for (PollingAccountRegistry.PollingAccount account : accounts) {
            snapshots.put(account.username(), pollAccount(account));
        }
        lastPollAt = Instant.now();
        lastSnapshots = Collections.unmodifiableMap(snapshots);
    }

    public Optional<Instant> lastPollAt() {
        return Optional.ofNullable(lastPollAt);
    }

    public Map<String, CommunicationSyncSnapshot> lastSnapshots() {
        return lastSnapshots;
    }

    private CommunicationSyncSnapshot pollAccount(PollingAccountRegistry.PollingAccount account) {
        try {
            TsaNetApiSession session = sessionFactory.openSessionForAccount(account.username(), account.password());
            session.auth().login(account.username(), account.password());
            CommunicationSyncSnapshot snapshot = session.collaborationRequests().syncCommunicationContext();
            log.info(
                "Polled account {} (sqlite={}): {}",
                account.username(),
                sessionFactory.sqlitePathForAccount(account.username()),
                snapshot.summarize()
            );
            return snapshot;
        } catch (RuntimeException ex) {
            log.error("Polling failed for account {}: {}", account.username(), ex.getMessage());
            return new CommunicationSyncSnapshot(0, 0, 0, 0, 0, false);
        }
    }
}

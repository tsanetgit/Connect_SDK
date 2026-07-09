package com.tsanet.application.sync;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.connectapi.dto.CommunicationSyncSnapshot;
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
    private final ApplicationUserAccountRegistry accountRegistry;

    private volatile Instant lastPollAt;
    private volatile Map<String, CommunicationSyncSnapshot> lastSnapshots = Map.of();

    public ConnectApiPollingCoordinator(
        TsaNetApiSessionFactory sessionFactory,
        ApplicationUserAccountRegistry accountRegistry
    ) {
        this.sessionFactory = sessionFactory;
        this.accountRegistry = accountRegistry;
    }

    public void pollConfiguredAccounts() {
        List<ApplicationUserAccount> accounts = PollingAccountRegistry.resolve(accountRegistry);
        if (accounts.isEmpty()) {
            log.warn("Polling skipped: configure tsaet.accounts in application.yml");
            return;
        }

        Map<String, CommunicationSyncSnapshot> snapshots = new LinkedHashMap<>();
        for (ApplicationUserAccount account : accounts) {
            snapshots.put(account.id(), pollAccount(account));
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

    private CommunicationSyncSnapshot pollAccount(ApplicationUserAccount account) {
        try {
            String password = requirePassword(account);
            TsaNetApiSession session = sessionFactory.openSessionWithSqlitePath(
                account.sqlitePath(),
                account.username(),
                password
            );
            session.auth().login(account.username(), password);
            CommunicationSyncSnapshot snapshot = session.collaborationRequests().syncCommunicationContext();
            log.info(
                "Polled application user {} ({}, sqlite={}): {}",
                account.id(),
                account.username(),
                account.sqlitePath(),
                snapshot.summarize()
            );
            return snapshot;
        } catch (RuntimeException ex) {
            log.error("Polling failed for application user {}: {}", account.id(), ex.getMessage());
            return new CommunicationSyncSnapshot(0, 0, 0, 0, 0, false);
        }
    }

    private static String requirePassword(ApplicationUserAccount account) {
        if (account.password() == null || account.password().isBlank()) {
            throw new IllegalStateException("Password is required for application user '" + account.id() + "'");
        }
        return account.password();
    }
}

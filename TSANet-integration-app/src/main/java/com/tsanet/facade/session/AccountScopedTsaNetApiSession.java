package com.tsanet.facade.session;

import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;
import java.util.Objects;
import java.util.Optional;

public final class AccountScopedTsaNetApiSession implements TsaNetApiSession, AccountSessionView {
    private final TsaNetApiSessionFactory sessionFactory;
    private final Optional<ConfiguredCredentials> configuredCredentials;

    private volatile TsaNetApiSession delegate;
    private volatile String activeAccountLabel;

    public AccountScopedTsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        Optional<ConfiguredCredentials> configuredCredentials
    ) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.configuredCredentials = configuredCredentials != null ? configuredCredentials : Optional.empty();
    }

    @Override
    public Optional<String> activeAccountLabel() {
        return Optional.ofNullable(activeAccountLabel);
    }

    @Override
    public Optional<String> activeSqlitePath() {
        return activeAccountLabel().map(sessionFactory::sqlitePathForLabel);
    }

    @Override
    public AuthFacade auth() {
        return new AccountAuthFacade();
    }

    @Override
    public CollaborationRequestsFacade collaborationRequests() {
        return requireDelegate().collaborationRequests();
    }

    @Override
    public CaseNotesFacade caseNotes() {
        return requireDelegate().caseNotes();
    }

    @Override
    public CaseResponsesFacade caseResponses() {
        return requireDelegate().caseResponses();
    }

    @Override
    public UserFacade users() {
        return requireDelegate().users();
    }

    @Override
    public WebhooksFacade webhooks() {
        return requireDelegate().webhooks();
    }

    @Override
    public PartnersFacade partners() {
        return requireDelegate().partners();
    }

    @Override
    public AttachmentsFacade attachments() {
        return requireDelegate().attachments();
    }

    private synchronized void activateAccount(String username, String password) {
        String label = sessionFactory.sessionLabelForAccount(username);
        if (delegate != null && label.equals(activeAccountLabel)) {
            return;
        }
        delegate = sessionFactory.openSessionForAccount(username, password);
        activeAccountLabel = label;
    }

    void bindAccountForTesting(String username, String password) {
        activateAccount(username, password);
    }

    private TsaNetApiSession requireDelegate() {
        TsaNetApiSession current = delegate;
        if (current == null) {
            throw new IllegalStateException("No account session. Use 'login' first.");
        }
        return current;
    }

    private final class AccountAuthFacade implements AuthFacade {
        @Override
        public String login(String username, String password) {
            activateAccount(username, password);
            return requireDelegate().auth().login(username, password);
        }

        @Override
        public String loginWithConfiguredCredentials() {
            ConfiguredCredentials credentials = configuredCredentials.orElseThrow(
                () -> new IllegalStateException("Configured username and password are required")
            );
            return login(credentials.username(), credentials.password());
        }

        @Override
        public boolean isAuthorized() {
            return delegate != null && delegate.auth().isAuthorized();
        }

        @Override
        public Optional<String> currentUsername() {
            if (delegate == null) {
                return Optional.empty();
            }
            return delegate.auth().currentUsername();
        }

        @Override
        public Optional<String> currentBearerToken() {
            if (delegate == null) {
                return Optional.empty();
            }
            return delegate.auth().currentBearerToken();
        }

        @Override
        public void logout() {
            if (delegate != null) {
                delegate.auth().logout();
            }
        }
    }

    public record ConfiguredCredentials(String username, String password) {
    }
}

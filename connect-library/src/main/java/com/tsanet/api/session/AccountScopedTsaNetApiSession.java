package com.tsanet.api.session;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import com.tsanet.api.TsaNetApiSession;
import com.tsanet.api.TsaNetApiSessionFactory;
import com.tsanet.api.auth.AuthMode;
import com.tsanet.api.auth.PasswordAuthConfig;
import com.tsanet.api.facade.AttachmentsFacade;
import com.tsanet.api.facade.AuthFacade;
import com.tsanet.api.facade.CaseNotesFacade;
import com.tsanet.api.facade.CaseResponsesFacade;
import com.tsanet.api.facade.CollaborationRequestsFacade;
import com.tsanet.api.facade.PartnersFacade;
import com.tsanet.api.facade.UserFacade;
import com.tsanet.api.facade.WebhooksFacade;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class AccountScopedTsaNetApiSession implements TsaNetApiSession, AccountSessionView {
    private final TsaNetApiSessionFactory sessionFactory;
    private final ApplicationUserAccountRegistry accountRegistry;

    private volatile TsaNetApiSession delegate;
    private volatile String activeAccountId;
    private volatile String activeSqlitePath;

    public AccountScopedTsaNetApiSession(
        TsaNetApiSessionFactory sessionFactory,
        ApplicationUserAccountRegistry accountRegistry
    ) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.accountRegistry = Objects.requireNonNull(accountRegistry, "accountRegistry");
    }

    @Override
    public Optional<String> activeAccountLabel() {
        return Optional.ofNullable(activeAccountId);
    }

    @Override
    public Optional<String> activeSqlitePath() {
        return Optional.ofNullable(activeSqlitePath);
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

    private synchronized void activateAccount(ApplicationUserAccount account) {
        if (delegate != null && account.id().equals(activeAccountId)) {
            return;
        }
        delegate = sessionFactory.openSessionForApplicationUser(account);
        activeAccountId = account.id();
        activeSqlitePath = account.sqlitePath();
    }

    void bindAccountForTesting(String accountId) {
        activateAccount(accountRegistry.requireById(accountId));
    }

    private TsaNetApiSession requireDelegate() {
        TsaNetApiSession current = delegate;
        if (current == null) {
            throw new IllegalStateException("No account session. Use 'login' or 'authenticate' first.");
        }
        return current;
    }

    private final class AccountAuthFacade implements AuthFacade {
        @Override
        public String authenticate() {
            ApplicationUserAccount account = accountRegistry.defaultAccount().orElseThrow(
                () -> new IllegalStateException("No application users configured under tsanet.accounts")
            );
            activateAccount(account);
            return requireDelegate().auth().authenticate();
        }

        @Override
        public String login(String username, String password) {
            ApplicationUserAccount account = accountRegistry.requireByUsername(username);
            if (account.auth().mode() != AuthMode.CONNECT1_PASSWORD) {
                throw new IllegalStateException(
                    "Account '" + account.id() + "' uses client-credentials auth. Use authenticate()."
                );
            }
            activateAccount(account);
            return requireDelegate().auth().login(username, password);
        }

        @Override
        public String loginWithConfiguredCredentials() {
            return authenticate();
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
        public Optional<String> currentAccountId() {
            if (delegate == null) {
                return Optional.empty();
            }
            return delegate.auth().currentAccountId();
        }

        @Override
        public Optional<AuthMode> authMode() {
            if (delegate == null) {
                return Optional.empty();
            }
            return delegate.auth().authMode();
        }

        @Override
        public Optional<Instant> tokenExpiresAt() {
            if (delegate == null) {
                return Optional.empty();
            }
            return delegate.auth().tokenExpiresAt();
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
}

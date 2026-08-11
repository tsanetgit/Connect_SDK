package com.tsanet.api;

import com.tsanet.api.auth.PasswordAuthConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ApplicationUserAccountRegistry {
    private final List<ApplicationUserAccount> accounts;
    private final Map<String, ApplicationUserAccount> byId;
    private final Map<String, ApplicationUserAccount> byUsername;
    private final ApplicationUserAccount defaultAccount;

    private ApplicationUserAccountRegistry(List<ApplicationUserAccount> accounts) {
        this.accounts = List.copyOf(accounts);
        Map<String, ApplicationUserAccount> idIndex = new LinkedHashMap<>();
        Map<String, ApplicationUserAccount> usernameIndex = new LinkedHashMap<>();
        for (ApplicationUserAccount account : this.accounts) {
            idIndex.put(account.id(), account);
            if (account.auth() instanceof PasswordAuthConfig passwordAuth) {
                usernameIndex.put(passwordAuth.username().trim().toLowerCase(), account);
            }
        }
        this.byId = Map.copyOf(idIndex);
        this.byUsername = Map.copyOf(usernameIndex);
        this.defaultAccount = this.accounts.isEmpty() ? null : this.accounts.getFirst();
    }

    public static ApplicationUserAccountRegistry of(List<ApplicationUserAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return empty();
        }
        return new ApplicationUserAccountRegistry(accounts);
    }

    public static ApplicationUserAccountRegistry fromLegacyAuth(String username, String password, String sqlitePath) {
        return of(List.of(ApplicationUserAccount.passwordAccount("default", sqlitePath, username, password)));
    }

    public static ApplicationUserAccountRegistry empty() {
        return new ApplicationUserAccountRegistry(List.of());
    }

    public List<ApplicationUserAccount> all() {
        return accounts;
    }

    public Optional<ApplicationUserAccount> findById(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(accountId.trim()));
    }

    public ApplicationUserAccount requireById(String accountId) {
        return findById(accountId).orElseThrow(
            () -> new IllegalArgumentException(
                "Unknown application user id '" + accountId + "'. Configure it under tsanet.accounts in application.yml."
            )
        );
    }

    public Optional<ApplicationUserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUsername.get(username.trim().toLowerCase()));
    }

    public ApplicationUserAccount requireByUsername(String username) {
        return findByUsername(username).orElseThrow(
            () -> new IllegalArgumentException(
                "Unknown application user '" + username + "'. Configure it under tsanet.accounts in application.yml."
            )
        );
    }

    public Optional<ApplicationUserAccount> defaultAccount() {
        return Optional.ofNullable(defaultAccount);
    }

    public boolean isEmpty() {
        return accounts.isEmpty();
    }
}

package com.tsanet.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ApplicationUserAccountRegistry {
    private final List<ApplicationUserAccount> accounts;
    private final Map<String, ApplicationUserAccount> byUsername;
    private final ApplicationUserAccount defaultAccount;

    private ApplicationUserAccountRegistry(List<ApplicationUserAccount> accounts) {
        this.accounts = List.copyOf(accounts);
        Map<String, ApplicationUserAccount> index = new LinkedHashMap<>();
        for (ApplicationUserAccount account : this.accounts) {
            index.put(account.username().trim().toLowerCase(), account);
        }
        this.byUsername = Map.copyOf(index);
        this.defaultAccount = this.accounts.isEmpty() ? null : this.accounts.getFirst();
    }

    public static ApplicationUserAccountRegistry of(List<ApplicationUserAccount> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return empty();
        }
        return new ApplicationUserAccountRegistry(accounts);
    }

    public static ApplicationUserAccountRegistry fromLegacyAuth(String username, String password, String sqlitePath) {
        return of(List.of(new ApplicationUserAccount("default", username, password, sqlitePath)));
    }

    public static ApplicationUserAccountRegistry empty() {
        return new ApplicationUserAccountRegistry(List.of());
    }

    public List<ApplicationUserAccount> all() {
        return accounts;
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
                "Unknown application user '" + username + "'. Configure it under tsaet.accounts in application.yml."
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

package com.tsanet.api;

import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.util.Optional;

public record ApplicationUserAccount(String id, String sqlitePath, AccountAuthConfig auth) {
    public ApplicationUserAccount {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("account id is required");
        }
        if (sqlitePath == null || sqlitePath.isBlank()) {
            throw new IllegalArgumentException("account sqlitePath is required");
        }
        if (auth == null) {
            throw new IllegalArgumentException("account auth is required");
        }
    }

    public static ApplicationUserAccount passwordAccount(String id, String sqlitePath, String username, String password) {
        return new ApplicationUserAccount(id, sqlitePath, new PasswordAuthConfig(username, password));
    }

    public String usernameForDisplay() {
        if (auth instanceof PasswordAuthConfig passwordAuth) {
            return passwordAuth.username();
        }
        return id;
    }

    public Optional<String> username() {
        if (auth instanceof PasswordAuthConfig passwordAuth) {
            return Optional.of(passwordAuth.username());
        }
        return Optional.empty();
    }

    public Optional<String> password() {
        if (auth instanceof PasswordAuthConfig passwordAuth) {
            return Optional.of(passwordAuth.password());
        }
        return Optional.empty();
    }
}

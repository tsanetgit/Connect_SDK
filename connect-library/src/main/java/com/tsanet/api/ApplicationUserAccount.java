package com.tsanet.api;

import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * @param allowedReceiverCompanyIds the companies this account may deliver V2 attachments to;
 *                                  empty means unrestricted
 */
public record ApplicationUserAccount(String id, String sqlitePath, AccountAuthConfig auth, Set<Long> allowedReceiverCompanyIds) {
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
        allowedReceiverCompanyIds = allowedReceiverCompanyIds == null ? Set.of() : Set.copyOf(allowedReceiverCompanyIds);
    }

    /** An account with no receiver allowlist (unrestricted). */
    public ApplicationUserAccount(String id, String sqlitePath, AccountAuthConfig auth) {
        this(id, sqlitePath, auth, Set.of());
    }

    /** This account with the given receiver allowlist; null or empty means unrestricted. */
    public ApplicationUserAccount withAllowedReceiverCompanyIds(Collection<Long> companyIds) {
        return new ApplicationUserAccount(id, sqlitePath, auth, companyIds == null ? Set.of() : Set.copyOf(companyIds));
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

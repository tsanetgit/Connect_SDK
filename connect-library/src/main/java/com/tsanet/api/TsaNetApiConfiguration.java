package com.tsanet.api;

import com.tsanet.api.auth.AccountAuthConfig;
import com.tsanet.api.auth.PasswordAuthConfig;
import java.util.Set;

/**
 * @param allowedReceiverCompanyIds the companies this account may deliver V2 attachments to;
 *                                  empty means unrestricted. Checked against the receiving company
 *                                  of a case this account sent, before any grant is requested.
 */
public record TsaNetApiConfiguration(
    String apiBaseUrl,
    String sqlitePath,
    String accountId,
    AccountAuthConfig auth,
    Set<Long> allowedReceiverCompanyIds
) {
    public TsaNetApiConfiguration {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }
        if (sqlitePath == null || sqlitePath.isBlank()) {
            throw new IllegalArgumentException("sqlitePath is required");
        }
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (auth == null) {
            throw new IllegalArgumentException("auth is required");
        }
        if (allowedReceiverCompanyIds != null && allowedReceiverCompanyIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("allowedReceiverCompanyIds must not contain null");
        }
        allowedReceiverCompanyIds = allowedReceiverCompanyIds == null ? Set.of() : Set.copyOf(allowedReceiverCompanyIds);
    }

    /** A configuration with no receiver allowlist (unrestricted). */
    public TsaNetApiConfiguration(String apiBaseUrl, String sqlitePath, String accountId, AccountAuthConfig auth) {
        this(apiBaseUrl, sqlitePath, accountId, auth, Set.of());
    }

    public static TsaNetApiConfiguration of(String apiBaseUrl, String sqlitePath, String username, String password) {
        return new TsaNetApiConfiguration(
            apiBaseUrl,
            sqlitePath,
            "default",
            new PasswordAuthConfig(username, password)
        );
    }

    public static TsaNetApiConfiguration forAccount(
        String apiBaseUrl,
        String sqlitePath,
        String accountId,
        AccountAuthConfig auth
    ) {
        return new TsaNetApiConfiguration(apiBaseUrl, sqlitePath, accountId, auth);
    }

    public String username() {
        if (auth instanceof PasswordAuthConfig passwordAuth) {
            return passwordAuth.username();
        }
        return null;
    }

    public String password() {
        if (auth instanceof PasswordAuthConfig passwordAuth) {
            return passwordAuth.password();
        }
        return null;
    }
}

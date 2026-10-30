package com.tsanet.facade.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectFacadePropertiesRedactionTest {
    @Test
    void nestedCredentialsRemainAccessibleButNeverAppearInOuterDiagnostics() {
        String legacyPassword = "synthetic-app-legacy-password-private";
        String accountPassword = "synthetic-app-account-password-private";
        String authPassword = "synthetic-app-auth-password-private";
        String clientSecret = "synthetic-app-client-secret-private";
        String tokenUrl = "https://user:" + "synthetic-app-url-private" + "@example.invalid/token";
        var auth = new ConnectFacadeProperties.Auth("user", legacyPassword);
        var accountAuth = new ConnectFacadeProperties.AccountAuthProperties(
            "client-credentials", "user", authPassword, "tenant", tokenUrl, "client", clientSecret,
            "audience", "scope");
        var account = new ConnectFacadeProperties.ApplicationUserAccountConfig(
            "test-account", "test.db", "user", accountPassword, accountAuth, List.of(17L));
        var properties = new ConnectFacadeProperties(null, auth, null, List.of(account));

        assertThat(auth.toString()).isEqualTo("Auth[<redacted>]");
        assertThat(accountAuth.toString()).isEqualTo("AccountAuthProperties[<redacted>]");
        assertThat(account.toString()).isEqualTo("ApplicationUserAccountConfig[<redacted>]");
        assertThat(properties.toString()).contains("<redacted>")
            .doesNotContain(legacyPassword, accountPassword, authPassword, clientSecret, tokenUrl,
                "synthetic-app-url-private");
        assertThat(auth.username()).isEqualTo("user");
        assertThat(auth.password()).isEqualTo(legacyPassword);
        assertThat(auth.isConfigured()).isTrue();
        assertThat(account.id()).isEqualTo("test-account");
        assertThat(account.sqlitePath()).isEqualTo("test.db");
        assertThat(account.username()).isEqualTo("user");
        assertThat(account.password()).isEqualTo(accountPassword);
        assertThat(account.auth()).isSameAs(accountAuth);
        assertThat(account.allowedReceiverCompanyIds()).containsExactly(17L);
        assertThat(accountAuth.type()).isEqualTo("client-credentials");
        assertThat(accountAuth.username()).isEqualTo("user");
        assertThat(accountAuth.password()).isEqualTo(authPassword);
        assertThat(accountAuth.tenantId()).isEqualTo("tenant");
        assertThat(accountAuth.clientId()).isEqualTo("client");
        assertThat(accountAuth.clientSecret()).isEqualTo(clientSecret);
        assertThat(accountAuth.tokenUrl()).isEqualTo(tokenUrl);
        assertThat(accountAuth.audience()).isEqualTo("audience");
        assertThat(accountAuth.scope()).isEqualTo("scope");
        assertThat(properties.auth()).isSameAs(auth);
        assertThat(properties.accounts()).containsExactly(account);
    }
}

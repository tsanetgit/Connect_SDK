package com.tsanet.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.application.setup.ConnectApiDatabaseProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationRecordRedactionTest {
    @Test
    void applicationCredentialsRemainAccessibleButNeverAppearInOuterDiagnostics() {
        String legacyPassword = "test-synthetic-demo-legacy-password-private";
        String accountPassword = "test-synthetic-demo-account-password-private";
        String authPassword = "test-synthetic-demo-auth-password-private";
        String clientSecret = "test-synthetic-demo-client-secret-private";
        String tokenUrl = "https://user:" + "synthetic-demo-url-private" + "@example.invalid/token";
        var auth = new TsaNetApplicationProperties.Auth("user", legacyPassword);
        var accountAuth = new TsaNetApplicationProperties.AccountAuthProperties(
            "client-credentials", "user", authPassword, "tenant", tokenUrl, "client", clientSecret,
            "audience", "scope");
        var account = new TsaNetApplicationProperties.ApplicationUserAccountConfig(
            "test-account", "test.db", "user", accountPassword, accountAuth);
        var properties = new TsaNetApplicationProperties(null, auth, null, List.of(account));

        assertThat(auth.toString()).isEqualTo("Auth[<redacted>]");
        assertThat(accountAuth.toString()).isEqualTo("AccountAuthProperties[<redacted>]");
        assertThat(account.toString()).isEqualTo("ApplicationUserAccountConfig[<redacted>]");
        assertThat(properties.toString()).contains("<redacted>")
            .doesNotContain(legacyPassword, accountPassword, authPassword, clientSecret, tokenUrl,
                "synthetic-demo-url-private");
        assertThat(auth.username()).isEqualTo("user");
        assertThat(auth.password()).isEqualTo(legacyPassword);
        assertThat(auth.isConfigured()).isTrue();
        assertThat(account.id()).isEqualTo("test-account");
        assertThat(account.sqlitePath()).isEqualTo("test.db");
        assertThat(account.username()).isEqualTo("user");
        assertThat(account.password()).isEqualTo(accountPassword);
        assertThat(account.auth()).isSameAs(accountAuth);
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

    @Test
    void scenarioCredentialsAreRedactedIndividuallyAndThroughOuterProperties() {
        String acmePassword = "test-synthetic-scenario-acme-password-private";
        String betaPassword = "test-synthetic-scenario-beta-password-private";
        var acme = new TsaNetScenarioProperties.CompanyCredentials("acme-user", acmePassword);
        var beta = new TsaNetScenarioProperties.CompanyCredentials("beta-user", betaPassword);
        var properties = new TsaNetScenarioProperties(false, acme, beta);

        assertThat(acme.toString()).isEqualTo("CompanyCredentials[<redacted>]");
        assertThat(beta.toString()).isEqualTo("CompanyCredentials[<redacted>]");
        assertThat(properties.toString()).contains("<redacted>").doesNotContain(acmePassword, betaPassword);
        assertThat(acme.username()).isEqualTo("acme-user");
        assertThat(acme.password()).isEqualTo(acmePassword);
        assertThat(beta.username()).isEqualTo("beta-user");
        assertThat(beta.password()).isEqualTo(betaPassword);
        assertThat(properties.acme()).isSameAs(acme);
        assertThat(properties.beta()).isSameAs(beta);
    }

    @Test
    void databaseCredentialsAndCredentialBearingUrlAreRedactedThroughOuterProperties() {
        String password = "test-synthetic-database-password-private";
        String url = "jdbc:postgresql://example.invalid/test?" + "password" + "="
            + "synthetic-database-url-private";
        var database = new ConnectApiDatabaseProperties.ConnectDb(url, "test-user", password);
        var properties = new ConnectApiDatabaseProperties(null, database);

        assertThat(database.toString()).isEqualTo("ConnectDb[<redacted>]");
        assertThat(properties.toString()).contains("<redacted>")
            .doesNotContain(password, url, "synthetic-database-url-private");
        assertThat(database.url()).isEqualTo(url);
        assertThat(database.username()).isEqualTo("test-user");
        assertThat(database.password()).isEqualTo(password);
        assertThat(properties.connectDb()).isSameAs(database);
    }
}

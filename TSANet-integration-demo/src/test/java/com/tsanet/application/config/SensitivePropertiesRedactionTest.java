package com.tsanet.application.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tsanet.application.setup.ConnectApiDatabaseProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensitivePropertiesRedactionTest {
    @Test
    void redactsLegacyAndNestedCredentialsWithoutChangingAccessors() {
        var auth = new TsaNetApplicationProperties.Auth("sample-user", "sample-password");
        var accountAuth = new TsaNetApplicationProperties.AccountAuthProperties(
            "client-credentials", "sample-user", "sample-password", "sample-tenant",
            "https://example.invalid/token", "sample-client", "sample-client-secret", "sample-audience", "sample-scope"
        );
        var account = new TsaNetApplicationProperties.ApplicationUserAccountConfig(
            "sample-account", "sample.db", "sample-user", "sample-password", accountAuth
        );
        var properties = new TsaNetApplicationProperties(null, auth, null, List.of(account));
        var company = new TsaNetScenarioProperties.CompanyCredentials("sample-user", "sample-password");
        var database = new ConnectApiDatabaseProperties.ConnectDb("sample-jdbc-url", "sample-user", "sample-password");

        assertEquals("sample-password", auth.password());
        assertEquals("sample-password", account.password());
        assertEquals("sample-password", accountAuth.password());
        assertEquals("sample-client-secret", accountAuth.clientSecret());
        assertEquals("https://example.invalid/token", accountAuth.tokenUrl());
        assertEquals(accountAuth, account.auth());
        assertEquals("sample-password", company.password());
        assertEquals("sample-password", database.password());
        assertEquals("sample-jdbc-url", database.url());
        for (Object value : List.of(auth, accountAuth, account, properties, company, database,
            new TsaNetScenarioProperties(false, company, company),
            new ConnectApiDatabaseProperties(null, database))) {
            assertRedacted(value);
        }
    }

    @Test
    void formatsAbsentCredentialsSafely() {
        assertRedacted(new TsaNetApplicationProperties.Auth(null, null));
        assertRedacted(new TsaNetApplicationProperties.AccountAuthProperties(
            null, null, null, null, null, null, null, null, null));
        assertRedacted(new TsaNetApplicationProperties.ApplicationUserAccountConfig(null, null, null, null, null));
        assertRedacted(new TsaNetScenarioProperties.CompanyCredentials(null, null));
        assertRedacted(new ConnectApiDatabaseProperties.ConnectDb(null, null, null));
    }

    private static void assertRedacted(Object value) {
        String text = value.toString();
        assertTrue(text.contains("<redacted>"));
        assertFalse(text.contains("sample-password"));
        assertFalse(text.contains("sample-client-secret"));
        assertFalse(text.contains("https://example.invalid/token"));
        assertFalse(text.contains("sample-jdbc-url"));
    }
}

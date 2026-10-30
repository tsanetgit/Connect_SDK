package com.tsanet.facade.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SensitivePropertiesRedactionTest {
    @Test
    void redactsLegacyAndNestedCredentialsWithoutChangingAccessors() {
        var auth = new ConnectFacadeProperties.Auth("sample-user", "sample-password");
        var accountAuth = new ConnectFacadeProperties.AccountAuthProperties(
            "client-credentials", "sample-user", "sample-password", "sample-tenant",
            "https://example.invalid/token", "sample-client", "sample-client-secret", "sample-audience", "sample-scope"
        );
        var account = new ConnectFacadeProperties.ApplicationUserAccountConfig(
            "sample-account", "sample.db", "sample-user", "sample-password", accountAuth, List.of()
        );
        var properties = new ConnectFacadeProperties(null, auth, null, List.of(account));

        assertEquals("sample-password", auth.password());
        assertEquals("sample-password", account.password());
        assertEquals("sample-password", accountAuth.password());
        assertEquals("sample-client-secret", accountAuth.clientSecret());
        assertEquals("https://example.invalid/token", accountAuth.tokenUrl());
        assertEquals(accountAuth, account.auth());
        assertEquals(List.of(), account.allowedReceiverCompanyIds());
        for (Object value : List.of(auth, accountAuth, account, properties)) {
            assertRedacted(value);
        }
    }

    @Test
    void formatsAbsentCredentialsSafely() {
        assertRedacted(new ConnectFacadeProperties.Auth(null, null));
        assertRedacted(new ConnectFacadeProperties.AccountAuthProperties(
            null, null, null, null, null, null, null, null, null));
        assertRedacted(new ConnectFacadeProperties.ApplicationUserAccountConfig(null, null, null, null, null, null));
    }

    private static void assertRedacted(Object value) {
        String text = value.toString();
        assertTrue(text.contains("<redacted>"));
        assertFalse(text.contains("sample-password"));
        assertFalse(text.contains("sample-client-secret"));
        assertFalse(text.contains("https://example.invalid/token"));
    }
}

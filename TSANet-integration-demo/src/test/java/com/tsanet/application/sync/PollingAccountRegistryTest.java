package com.tsanet.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import org.junit.jupiter.api.Test;

class PollingAccountRegistryTest {
    @Test
    void itReturnsAllConfiguredAccounts() {
        ApplicationUserAccountRegistry registry = ApplicationUserAccountRegistry.of(
            java.util.List.of(
                ApplicationUserAccount.passwordAccount("acme", "/tmp/acme.db", "api@appko.com", "pass-a"),
                ApplicationUserAccount.passwordAccount("beta", "/tmp/beta.db", "beta@corp.com", "pass-b")
            )
        );

        assertThat(PollingAccountRegistry.resolve(registry))
            .extracting(ApplicationUserAccount::id)
            .containsExactly("acme", "beta");
    }
}

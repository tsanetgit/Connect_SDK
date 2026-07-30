package com.tsanet.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.api.ApplicationUserAccount;
import com.tsanet.api.ApplicationUserAccountRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollingAccountRegistryTest {
    @Test
    void itReturnsConfiguredApplicationUsers() {
        ApplicationUserAccountRegistry registry = ApplicationUserAccountRegistry.of(
            List.of(
                new ApplicationUserAccount("acme", "api@appko.com", "pass-a", "/tmp/acme.db"),
                new ApplicationUserAccount("beta", "beta@corp.com", "pass-b", "/tmp/beta.db")
            )
        );

        assertThat(PollingAccountRegistry.resolve(registry))
            .extracting(ApplicationUserAccount::id)
            .containsExactly("acme", "beta");
    }
}

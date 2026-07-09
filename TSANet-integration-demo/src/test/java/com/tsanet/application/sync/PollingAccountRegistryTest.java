package com.tsanet.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.tsanet.application.config.TsaNetApplicationProperties;
import com.tsanet.application.config.TsaNetScenarioProperties;
import org.junit.jupiter.api.Test;

class PollingAccountRegistryTest {
    @Test
    void itDeduplicatesConfiguredAccountsByUsername() {
        var applicationProperties = new TsaNetApplicationProperties(
            new TsaNetApplicationProperties.Api("http://localhost:8080"),
            new TsaNetApplicationProperties.Auth("api@appko.com", "pass-a"),
            new TsaNetApplicationProperties.Storage("/tmp/data.db")
        );
        var scenarioProperties = new TsaNetScenarioProperties(
            false,
            new TsaNetScenarioProperties.CompanyCredentials("api@appko.com", "pass-a"),
            new TsaNetScenarioProperties.CompanyCredentials("beta@corp.com", "pass-b")
        );

        var accounts = PollingAccountRegistry.resolve(applicationProperties, scenarioProperties, true);

        assertThat(accounts).hasSize(2);
        assertThat(accounts).extracting(PollingAccountRegistry.PollingAccount::username)
            .containsExactly("api@appko.com", "beta@corp.com");
    }

    @Test
    void itCanPollOnlyPrimaryAuthAccount() {
        var applicationProperties = new TsaNetApplicationProperties(
            new TsaNetApplicationProperties.Api("http://localhost:8080"),
            new TsaNetApplicationProperties.Auth("primary@corp.com", "pass"),
            new TsaNetApplicationProperties.Storage("/tmp/data.db")
        );
        var scenarioProperties = new TsaNetScenarioProperties(
            false,
            new TsaNetScenarioProperties.CompanyCredentials("acme@corp.com", "pass"),
            new TsaNetScenarioProperties.CompanyCredentials("beta@corp.com", "pass")
        );

        var accounts = PollingAccountRegistry.resolve(applicationProperties, scenarioProperties, false);

        assertThat(accounts).singleElement()
            .extracting(PollingAccountRegistry.PollingAccount::username)
            .isEqualTo("primary@corp.com");
    }
}

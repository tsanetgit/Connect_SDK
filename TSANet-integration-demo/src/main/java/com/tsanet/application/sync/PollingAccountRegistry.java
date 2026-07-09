package com.tsanet.application.sync;

import com.tsanet.application.config.TsaNetApplicationProperties;
import com.tsanet.application.config.TsaNetScenarioProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PollingAccountRegistry {
    private PollingAccountRegistry() {
    }

    public static List<PollingAccount> resolve(
        TsaNetApplicationProperties applicationProperties,
        TsaNetScenarioProperties scenarioProperties,
        boolean includeScenarioAccounts
    ) {
        Map<String, PollingAccount> byUsername = new LinkedHashMap<>();
        addIfPresent(byUsername, applicationProperties.auth().username(), applicationProperties.auth().password());
        if (includeScenarioAccounts && scenarioProperties != null) {
            addIfPresent(byUsername, scenarioProperties.acme().username(), scenarioProperties.acme().password());
            addIfPresent(byUsername, scenarioProperties.beta().username(), scenarioProperties.beta().password());
        }
        return List.copyOf(byUsername.values());
    }

    private static void addIfPresent(Map<String, PollingAccount> byUsername, String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return;
        }
        byUsername.putIfAbsent(username.trim(), new PollingAccount(username.trim(), password));
    }

    public record PollingAccount(String username, String password) {
    }
}

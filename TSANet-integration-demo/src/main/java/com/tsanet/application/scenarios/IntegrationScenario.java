package com.tsanet.application.scenarios;

/**
 * Contract for an integration-demo scenario executed against a live Connect API stack.
 */
public interface IntegrationScenario {
    String name();

    void run();
}

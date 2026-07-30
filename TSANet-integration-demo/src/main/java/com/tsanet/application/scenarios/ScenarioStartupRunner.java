package com.tsanet.application.scenarios;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs integration scenarios once the application is ready.
 *
 * <p>Executes after {@link com.tsanet.application.setup.TestScenarioDatabaseSetup} (order 0).
 * Scenarios are ordered with {@link org.springframework.core.annotation.Order} on each
 * {@link IntegrationScenario} bean (forms, Acme create, Beta approve, Beta list, Beta add notes,
 * Acme read notes, attachments, webhook actions).
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "tsanet.scenarios", name = "run-on-startup", havingValue = "true")
public class ScenarioStartupRunner implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(ScenarioStartupRunner.class);

    private final List<IntegrationScenario> scenarios;

    public ScenarioStartupRunner(List<IntegrationScenario> scenarios) {
        this.scenarios = scenarios;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Running {} integration scenario(s) on application startup", scenarios.size());
        for (IntegrationScenario scenario : scenarios) {
            log.info("Starting scenario: {}", scenario.name());
            scenario.run();
        }
        log.info("Startup scenarios finished");
    }
}

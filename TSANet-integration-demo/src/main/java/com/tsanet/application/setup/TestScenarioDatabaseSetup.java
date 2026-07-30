package com.tsanet.application.setup;

import com.tsanet.application.setup.step.DatabaseSetupStep;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "tsanet.demo.setup", name = "enabled", havingValue = "true")
public class TestScenarioDatabaseSetup implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(TestScenarioDatabaseSetup.class);

    private final JdbcTemplate jdbcTemplate;
    private final List<DatabaseSetupStep> steps;

    public TestScenarioDatabaseSetup(JdbcTemplate jdbcTemplate, List<DatabaseSetupStep> steps) {
        this.jdbcTemplate = jdbcTemplate;
        this.steps = steps;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Starting Connect API database setup for integration-demo test scenarios");
        for (DatabaseSetupStep step : steps) {
            log.info("Applying setup step: {}", step.name());
            step.apply(jdbcTemplate);
        }
        log.info("Connect API database setup complete");
    }
}
